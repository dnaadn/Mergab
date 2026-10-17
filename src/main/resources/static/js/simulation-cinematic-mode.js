/* Mergab — cinematic simulation layer */
document.addEventListener("DOMContentLoaded", () => {
    "use strict";

    const $ = id => document.getElementById(id);

    const host = $("simulationMap");
    const toggle = $("simulationCinematicToggle");

    if (!host || !toggle) return;

    const NS = "http://www.w3.org/2000/svg";
    const WALK_API = "/api/v1/zone-walk-path/get-active-by-zone/";

    const state = {
        svg: null,
        layer: null,
        lanesGroup: null,
        streamGroup: null,
        walkersGroup: null,
        focusGroup: null,
        zones: [],
        rows: [],
        connections: [],
        walkwaysByZone: new Map(),
        walkers: [],
        streams: [],
        lanes: [],
        playing: false,
        enabled: toggle.checked,
        loadingWalkways: false,
        loadedZoneIds: new Set(),
        renderVersion: 0,
        sceneSignature: ""
    };

    const rand = (min, max) => min + Math.random() * (max - min);
    const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

    function svgNode(tag, attrs = {}) {
        const node = document.createElementNS(NS, tag);

        Object.entries(attrs).forEach(([key, value]) => {
            node.setAttribute(key, String(value));
        });

        return node;
    }

    function parsePoints(value, minimum = 2) {
        try {
            const points = typeof value === "string"
                ? JSON.parse(value)
                : value;

            if (!Array.isArray(points) || points.length < minimum) {
                return [];
            }

            return points.every(point =>
                Array.isArray(point) &&
                point.length === 2 &&
                point.every(n =>
                    typeof n === "number" &&
                    Number.isFinite(n) &&
                    n >= 0 && n <= 100
                )
            ) ? points : [];

        } catch (_) {
            return [];
        }
    }

    function hasPosition(zone) {
        return zone?.mapX != null &&
            zone?.mapY != null &&
            Number.isFinite(Number(zone.mapX)) &&
            Number.isFinite(Number(zone.mapY));
    }

    function routePath(points) {
        let d = `M ${points[0][0]} ${points[0][1]}`;

        if (points.length === 2) {
            return d + ` L ${points[1][0]} ${points[1][1]}`;
        }

        const smoothness = 0.12;

        for (let i = 0; i < points.length - 1; i++) {
            const previous = points[Math.max(0, i - 1)];
            const current = points[i];
            const next = points[i + 1];
            const following = points[Math.min(points.length - 1, i + 2)];

            const cp1x = current[0] + (next[0] - previous[0]) * smoothness;
            const cp1y = current[1] + (next[1] - previous[1]) * smoothness;
            const cp2x = next[0] - (following[0] - current[0]) * smoothness;
            const cp2y = next[1] - (following[1] - current[1]) * smoothness;

            d += ` C ${cp1x} ${cp1y}, ${cp2x} ${cp2y}, ${next[0]} ${next[1]}`;
        }

        return d;
    }

    function ensureLayer() {
        const svg = host.querySelector(".mqm-isvg");
        if (!svg) return false;

        if (state.svg !== svg) {
            state.layer?.remove();
            state.svg = svg;

            state.layer = svgNode("g", {
                id: "simulationCinematicLayer",
                "pointer-events": "none"
            });

            state.lanesGroup = svgNode("g", { id: "simulationLaneLayer" });
            state.streamGroup = svgNode("g", { id: "simulationStreamLayer" });
            state.walkersGroup = svgNode("g", { id: "simulationWalkerLayer" });
            state.focusGroup = svgNode("g", { id: "simulationFocusLayer" });

            state.layer.append(
                state.lanesGroup,
                state.streamGroup,
                state.walkersGroup,
                state.focusGroup
            );

            svg.appendChild(state.layer);
            state.lanes = [];
            state.walkers = [];
            state.streams = [];
        }

        state.layer.style.display = state.enabled ? "" : "none";
        return true;
    }

    function computeSceneSignature() {
        return [
            state.renderVersion,
            state.zones.map(zone => zone.id).join(","),
            state.connections
                .map(connection =>
                    connection.id ||
                    `${connection.fromZone?.id || connection.from_zone_id}-${connection.toZone?.id || connection.to_zone_id}`
                )
                .join(",")
        ].join("|");
    }

    function ensureSceneBuilt() {
        const signature = computeSceneSignature();

        if (signature === state.sceneSignature && state.lanes.length) {
            return;
        }

        state.sceneSignature = signature;
        buildScene();
        syncWalkers();
        syncFocus();
    }

    async function ensureWalkways(zones) {
        const missing = zones.filter(zone =>
            !state.loadedZoneIds.has(String(zone.id))
        );

        if (!missing.length || state.loadingWalkways) {
            return;
        }

        state.loadingWalkways = true;

        try {
            const results = await Promise.allSettled(
                missing.map(zone =>
                    fetch(WALK_API + encodeURIComponent(zone.id), {
                        credentials: "same-origin",
                        headers: { Accept: "application/json" },
                        cache: "no-store"
                    }).then(async response => {
                        const data = await response.json().catch(() => []);
                        if (!response.ok) {
                            throw new Error(`HTTP ${response.status}`);
                        }
                        return Array.isArray(data) ? data : [];
                    })
                )
            );

            results.forEach((result, index) => {
                const zoneId = String(missing[index].id);

                if (result.status === "fulfilled") {
                    state.loadedZoneIds.add(zoneId);

                    state.walkwaysByZone.set(
                        zoneId,
                        result.value
                    );
                } else {
                    console.error(
                        "Walkways loading failed for zone",
                        zoneId,
                        result.reason
                    );

                    // Don't mark failed requests as loaded.
                    // Allow retry on the next simulation update.
                    state.loadedZoneIds.delete(zoneId);
                    state.walkwaysByZone.delete(zoneId);
                }
            });

            state.renderVersion++;
            state.sceneSignature = "";
            ensureSceneBuilt();

        } finally {
            state.loadingWalkways = false;
        }
    }

    function addLane(pathPoints, kind, zoneId, data) {
        if (pathPoints.length < 2 || !ensureLayer()) return;

        const path = svgNode("path", {
            d: routePath(pathPoints),
            fill: "none",
            class: kind === "walk"
                ? "simulation-cinematic-lane simulation-cinematic-walk"
                : "simulation-cinematic-lane simulation-cinematic-connection"
        });

        state.lanesGroup.appendChild(path);

        const length = path.getTotalLength();
        if (!(length > 0)) return;

        state.lanes.push({
            node: path,
            kind,
            zoneId: String(zoneId),
            pathPoints,
            length,
            data
        });
    }

    function buildScene() {
        if (!ensureLayer()) return;

        state.lanesGroup.replaceChildren();
        state.streamGroup.replaceChildren();
        state.focusGroup.replaceChildren();
        state.lanes = [];
        state.streams = [];

        state.connections.forEach(connection => {
            const points = parsePoints(connection.pathPoints, 2);
            if (points.length < 2) return;

            addLane(
                points,
                "connection",
                connection?.fromZone?.id ??
                connection?.from_zone_id ??
                "connection",
                connection
            );

            const streamCount = Math.max(
                1,
                Math.min(
                    4,
                    Math.round(
                        Number(connection.maxVisitorsPerMinute || 0) / 3
                    )
                )
            );

            const lane = state.lanes[state.lanes.length - 1];
            if (!lane) return;

            for (let i = 0; i < streamCount; i++) {
                const dot = svgNode("circle", {
                    r: 0.40,
                    class: "simulation-stream-dot"
                });

                state.streamGroup.appendChild(dot);

                state.streams.push({
                    node: dot,
                    lane,
                    progress: i / streamCount,
                    speed: rand(0.05, 0.12)
                });
            }
        });

        state.zones.forEach(zone => {
            const zoneId = String(zone.id);
            const walkways = state.walkwaysByZone.get(zoneId) || [];

            walkways.forEach(walkway => {
                const points = parsePoints(walkway.pathPoints, 2);
                if (points.length < 2) return;

                addLane(points, "walk", zoneId, walkway);
            });
        });
    }

    function lanesForZone(zoneId) {
        const directWalks = state.lanes.filter(lane =>
            lane.kind === "walk" &&
            String(lane.zoneId) === String(zoneId)
        );

        if (directWalks.length) return directWalks;

        return state.lanes.filter(lane => {
            if (lane.kind !== "connection") return false;

            const connection = lane.data;

            return String(connection?.fromZone?.id ?? connection?.from_zone_id) === String(zoneId) ||
                String(connection?.toZone?.id ?? connection?.to_zone_id) === String(zoneId);
        });
    }

    function createWalker(zoneId) {
        const pool = lanesForZone(zoneId);
        if (!pool.length || !ensureLayer()) return null;

        const lane = pool[Math.floor(Math.random() * pool.length)];

        const node = svgNode("g", {
            class: "simulation-walker"
        });

        node.appendChild(svgNode("ellipse", {
            cx: 0,
            cy: 1.55,
            rx: 0.52,
            ry: 0.22,
            class: "simulation-walker-shadow"
        }));

        node.appendChild(svgNode("circle", {
            cx: 0,
            cy: -0.88,
            r: 0.34,
            class: "simulation-walker-head"
        }));

        node.appendChild(svgNode("path", {
            d: "M0,-0.48 L0,0.75 M0,0.02 L-0.44,0.50 M0,0.02 L0.44,0.50 M0,0.75 L-0.38,1.45 M0,0.75 L0.38,1.45",
            class: "simulation-walker-body"
        }));

        state.walkersGroup.appendChild(node);

        return {
            node,
            zoneId: String(zoneId),
            lane,
            progress: Math.random(),
            speed: rand(0.045, 0.105),
            direction: Math.random() > 0.5 ? 1 : -1,
            scale: rand(0.92, 1.18),
            sway: Math.random() * Math.PI * 2
        };
    }

    function syncWalkers() {
        if (!ensureLayer()) return;

        const byZone = new Map(
            state.rows
                .filter(row => row.zone?.id != null)
                .map(row => [String(row.zone.id), row])
        );

        const nextWalkers = [];

        state.zones.forEach(zone => {
            const zoneId = String(zone.id);
            const row = byZone.get(zoneId);
            const occupancy = Math.max(0, Number(row?.occupancy) || 0);
            const lanes = lanesForZone(zoneId);

            const target = !lanes.length || occupancy <= 0
                ? 0
                : Math.min(
                    16,
                    Math.max(
                        2,
                        Math.round(Math.sqrt(occupancy) * 0.85)
                    )
                );

            let owned = state.walkers.filter(
                walker => walker.zoneId === zoneId
            );

            while (owned.length < target) {
                const walker = createWalker(zoneId);
                if (!walker) break;
                owned.push(walker);
            }

            while (owned.length > target) {
                owned.pop()?.node.remove();
            }

            nextWalkers.push(...owned);
        });

        state.walkers
            .filter(walker => !nextWalkers.includes(walker))
            .forEach(walker => walker.node.remove());

        state.walkers = nextWalkers;
    }

    function syncFocus() {
        if (!ensureLayer()) return;

        state.focusGroup.replaceChildren();

        const ranked = state.rows
            .map(row => {
                const zone = state.zones.find(
                    item => String(item.id) === String(row.zone?.id)
                );

                const capacity = Number(
                    zone?.capacity ?? row.zone?.capacity ?? 0
                );

                const occupancy = Number(row.occupancy) || 0;

                const percent = capacity > 0
                    ? (occupancy / capacity) * 100
                    : 0;

                return { zone, percent, occupancy };
            })
            .filter(item => item.zone && hasPosition(item.zone))
            .sort((a, b) => b.percent - a.percent);

        const top = ranked[0];
        if (!top || top.occupancy <= 0) return;

        const x = Number(top.zone.mapX);
        const y = Number(top.zone.mapY);

        const group = svgNode("g", {
            transform: `translate(${x} ${y})`
        });

        group.appendChild(svgNode("circle", {
            r: 2.8,
            class: "simulation-focus-ring simulation-focus-ring-outer"
        }));

        group.appendChild(svgNode("circle", {
            r: 1.7,
            class: "simulation-focus-ring simulation-focus-ring-inner"
        }));

        const text = svgNode("text", {
            x: 0,
            y: -3.7,
            "text-anchor": "middle",
            class: "simulation-focus-label"
        });

        text.textContent = `الأعلى إشغالًا · ${top.zone.name}`;
        group.appendChild(text);

        state.focusGroup.appendChild(group);
    }

    function advance(dt) {
        if (!state.enabled || !ensureLayer()) return;

        state.walkers.forEach(walker => {
            const lanes = lanesForZone(walker.zoneId);
            if (!lanes.length) return;

            if (!lanes.includes(walker.lane)) {
                walker.lane = lanes[0];
            }

            walker.progress +=
                (walker.speed * dt / 1000) * walker.direction;

            if (walker.progress >= 1 || walker.progress <= 0) {
                walker.direction *= -1;
                walker.progress = clamp(walker.progress, 0, 1);

                if (Math.random() < 0.35) {
                    walker.lane =
                        lanes[Math.floor(Math.random() * lanes.length)];
                }
            }

            const distance = walker.lane.length * walker.progress;

            const point = walker.lane.node.getPointAtLength(distance);

            const before = walker.lane.node.getPointAtLength(
                clamp(distance - 0.35, 0, walker.lane.length)
            );

            const after = walker.lane.node.getPointAtLength(
                clamp(distance + 0.35, 0, walker.lane.length)
            );

            const angle = Math.atan2(
                after.y - before.y,
                after.x - before.x
            ) * 180 / Math.PI;

            const bob =
                Math.sin(Date.now() / 120 + walker.sway) * 0.10;

            walker.node.setAttribute(
                "transform",
                `translate(${point.x} ${point.y + bob}) rotate(${angle}) scale(${walker.scale})`
            );
        });

        state.streams.forEach(stream => {
            stream.progress =
                (stream.progress + stream.speed * dt / 1000) % 1;

            const distance = stream.lane.length * stream.progress;
            const point = stream.lane.node.getPointAtLength(distance);

            stream.node.setAttribute("cx", point.x);
            stream.node.setAttribute("cy", point.y);
        });
    }

    let lastTime = 0;

    function tick(time) {
        const dt = Math.min(42, time - (lastTime || time));
        lastTime = time;

        if (state.playing && state.enabled) {
            advance(dt);
        }

        requestAnimationFrame(tick);
    }

    toggle.addEventListener("change", () => {
        state.enabled = toggle.checked;

        if (state.layer) {
            state.layer.style.display = state.enabled ? "" : "none";
        }
    });

    document.addEventListener("mergab:simulation-frame", event => {
        state.zones = Array.isArray(event.detail?.zones)
            ? event.detail.zones
            : [];

        state.rows = Array.isArray(event.detail?.rows)
            ? event.detail.rows
            : [];

        state.connections = Array.isArray(event.detail?.connections)
            ? event.detail.connections
            : [];

        ensureLayer();
        ensureWalkways(state.zones);
        ensureSceneBuilt();
        syncWalkers();
        syncFocus();

        if (!state.playing) {
            advance(16);
        }
    });

    document.addEventListener("mergab:simulation-playback", event => {
        state.playing = Boolean(event.detail?.playing);
    });

    requestAnimationFrame(tick);
});