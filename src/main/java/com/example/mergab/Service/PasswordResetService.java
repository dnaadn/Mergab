package com.example.mergab.Service;

import com.example.mergab.Api.ApiException;
import com.example.mergab.DTO.ResetPasswordDTO;
import com.example.mergab.Model.PasswordResetCode;
import com.example.mergab.Model.User;
import com.example.mergab.Repository.PasswordResetCodeRepository;
import com.example.mergab.Repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;

// نسيت كلمة المرور: نرسل كود ٦ أرقام على الإيميل، صالح ١٠ دقائق
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final int CODE_MINUTES = 10;
    private static final int MAX_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final PasswordResetCodeRepository resetCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final NotificationService notificationService;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public void sendCode(String email) {
        User user = userRepository.findUserByEmail(email);
        // ما نقول إن الإيميل مو موجود، عشان محد يعرف مين مسجل عندنا
        if (user == null) {
            return;
        }

        // نلغي أي كود قديم قبل ما نرسل الجديد
        resetCodeRepository.deleteByUser_Id(user.getId());

        String code = String.format("%06d", random.nextInt(1_000_000));
        resetCodeRepository.save(new PasswordResetCode(
                null, user, passwordEncoder.encode(code),
                LocalDateTime.now().plusMinutes(CODE_MINUTES), 0));

        emailService.sendResetCode(user.getEmail(), user.getFullName(), code);
        notificationService.passwordResetCode(user, code);
    }

    // dontRollbackOn: عشان عدد المحاولات الغلط ينحفظ حتى لو رمينا خطأ
    @Transactional(dontRollbackOn = ApiException.class)
    public void resetPassword(ResetPasswordDTO dto) {
        User user = userRepository.findUserByEmail(dto.getEmail());
        if (user == null) {
            throw new ApiException("Invalid or expired code");
        }

        PasswordResetCode reset = resetCodeRepository.findFirstByUser_IdOrderByIdDesc(user.getId());
        if (reset == null || reset.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ApiException("Invalid or expired code");
        }

        // ٥ محاولات بس، عشان محد يجرب كل الأكواد
        if (reset.getAttempts() >= MAX_ATTEMPTS) {
            resetCodeRepository.delete(reset);
            throw new ApiException("Too many attempts, request a new code");
        }

        if (!passwordEncoder.matches(dto.getCode(), reset.getCodeHash())) {
            reset.setAttempts(reset.getAttempts() + 1);
            resetCodeRepository.save(reset);
            throw new ApiException("Invalid or expired code");
        }

        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        userRepository.save(user);

        // الكود يُستخدم مرة وحدة
        resetCodeRepository.deleteByUser_Id(user.getId());
    }
}
