package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.entity.AdminAccountEntity;
import com.macrotel.rapidstylers.pojo.AdminAccountData;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.repo.AdminAccountRepo;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.macrotel.rapidstylers.config.AppConstants.ERROR_STATUS_CODE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;
import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_MESSAGE;

/**
 * Admin account management. Every method is restricted to the ADMIN role via
 * method-level security (@PreAuthorize), in addition to the path-level gate in
 * JwtAuthFilter — the design's "no missing @PreAuthorize can expose an admin
 * route" guarantee for the least-trusted surface.
 */
@RestController
@RequestMapping("/rapid_stylers/admin/accounts")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAccountController {

    private final AdminAccountRepo adminAccountRepo;
    private final PasswordEncoder passwordEncoder;

    public AdminAccountController(AdminAccountRepo adminAccountRepo, PasswordEncoder passwordEncoder) {
        this.adminAccountRepo = adminAccountRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public ResponseEntity<BaseResponse> listAdmins() {
        List<Map<String, Object>> admins = adminAccountRepo.findAll().stream()
                .map(this::toView)
                .collect(Collectors.toList());
        BaseResponse response = new BaseResponse();
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(admins);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<BaseResponse> createAdmin(@Valid @RequestBody AdminAccountData data) {
        BaseResponse response = new BaseResponse();
        String email = data.getEmail().trim().toLowerCase();
        if (adminAccountRepo.existsByEmailIgnoreCase(email)) {
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("An admin with this email already exists.");
            response.setData(new Object[0]);
            return ResponseEntity.ok(response);
        }
        AdminAccountEntity account = new AdminAccountEntity();
        account.setEmail(email);
        account.setPasswordHash(passwordEncoder.encode(data.getPassword()));
        account.setEnabled(true);
        account.setRole("ADMIN");
        account = adminAccountRepo.save(account);
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(toView(account));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<BaseResponse> disableAdmin(@PathVariable Long id) {
        return setEnabled(id, false);
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<BaseResponse> enableAdmin(@PathVariable Long id) {
        return setEnabled(id, true);
    }

    private ResponseEntity<BaseResponse> setEnabled(Long id, boolean enabled) {
        BaseResponse response = new BaseResponse();
        AdminAccountEntity account = adminAccountRepo.findById(id).orElse(null);
        if (account == null) {
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Admin account not found.");
            response.setData(new Object[0]);
            return ResponseEntity.ok(response);
        }
        account.setEnabled(enabled);
        adminAccountRepo.save(account);
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(toView(account));
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> toView(AdminAccountEntity account) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", account.getId());
        view.put("email", account.getEmail());
        view.put("enabled", account.isEnabled());
        view.put("role", account.getRole());
        view.put("createdAt", account.getCreatedAt());
        return view;
    }
}