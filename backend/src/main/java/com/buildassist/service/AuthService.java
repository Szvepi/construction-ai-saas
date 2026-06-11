package com.buildassist.service;

import com.buildassist.dto.AuthDtos.AuthResponse;
import com.buildassist.dto.AuthDtos.LoginRequest;
import com.buildassist.dto.AuthDtos.RegisterRequest;
import com.buildassist.exception.AccountInactiveException;
import com.buildassist.exception.DuplicateEmailException;
import com.buildassist.exception.InvalidCredentialsException;
import com.buildassist.model.User;
import com.buildassist.repository.UserRepository;
import com.buildassist.security.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setActive(false);
        userRepository.save(user);

        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmail(email)
            .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        if (!user.isActive()) {
            throw new AccountInactiveException();
        }

        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtTokenProvider.createToken(user.getId(), user.getEmail());
        return new AuthResponse(token, user.getEmail());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }
}
