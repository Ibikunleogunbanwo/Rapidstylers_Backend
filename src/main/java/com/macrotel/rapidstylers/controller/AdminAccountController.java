package com.macrotel.rapidstylers.controller;

import com.macrotel.rapidstylers.config.AdminAccountInitializer;
import com.macrotel.rapidstylers.entity.AdminAccountEntity;
import com.macrotel.rapidstylers.pojo.AdminAccountData;
import com.macrotel.rapidstylers.pojo.AdminPasswordData;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import com.macrotel.rapidstylers.repo.AdminAccountRepo;
import com.macrotel.rapidstylers.service.StepUpService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
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

    private StepUpService stepUpService;

    public AdminAccountController(AdminAccountRepo adminAccountRepo, PasswordEncoder passwordEncoder) {
        this.adminAccountRepo = adminAccountRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Autowired
    public void setStepUpService(StepUpService stepUpService) {
        this.stepUpService = stepUpService;
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

    private ResponseEntity<BaseResponse> stepUpRequired() {
        BaseResponse response = new BaseResponse();
        response.setStatusCode("403");
        response.setMessage("Re-authentication required. Please re-enter your admin password.");
        response.setData(new Object[0]);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    /**
     * Sensitive admin-account mutations require step-up: the acting admin (from
     * the JWT subject, set as the accountId request attribute) must re-prove their
     * password via the X-Step-Up-Password header.
     */
    private ResponseEntity<BaseResponse> requireStepUp() {
        String actor = null;
        javax.servlet.http.HttpServletRequest request = null;
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes) {
            request = ((ServletRequestAttributes) attrs).getRequest();
            actor = (String) request.getAttribute("accountId");
        }
        String presented = request == null ? null : request.getHeader("X-Step-Up-Password");
        if (stepUpService == null || !stepUpService.verify(actor, presented)) {
            return stepUpRequired();
        }
        return null; // cleared
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
        String weakMessage = weakPasswordMessage(data.getPassword());
        if (weakMessage != null) {
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage(weakMessage);
            response.setData(new Object[0]);
            return ResponseEntity.ok(response);
        }
        ResponseEntity<BaseResponse> stepUp = requireStepUp();
        if (stepUp != null) return stepUp;
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

    @PostMapping("/{id}/password")
    public ResponseEntity<BaseResponse> changeAdminPassword(@PathVariable Long id,
                                                            @Valid @RequestBody AdminPasswordData data) {
        BaseResponse response = new BaseResponse();
        String weakMessage = weakPasswordMessage(data.getPassword());
        if (weakMessage != null) {
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage(weakMessage);
            response.setData(new Object[0]);
            return ResponseEntity.ok(response);
        }
        ResponseEntity<BaseResponse> stepUp = requireStepUp();
        if (stepUp != null) return stepUp;
        AdminAccountEntity account = adminAccountRepo.findById(id).orElse(null);
        if (account == null) {
            response.setStatusCode(ERROR_STATUS_CODE);
            response.setMessage("Admin account not found.");
            response.setData(new Object[0]);
            return ResponseEntity.ok(response);
        }
        account.setPasswordHash(passwordEncoder.encode(data.getPassword()));
        adminAccountRepo.save(account);
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
        ResponseEntity<BaseResponse> stepUp = requireStepUp();
        if (stepUp != null) return stepUp;
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

    /**
     * Rejects the well-known bootstrap default and other obviously weak values
     * so a default/placeholder password can never be persisted through the API
     * (mirrors AdminAccountInitializer's bootstrap guard). Returns null when the
     * password is acceptable.
     */
    private String weakPasswordMessage(String password) {
        String trimmed = password == null ? "" : password.trim();
        if (trimmed.length() < 8) {
            return "Password must be at least 8 characters";
        }
        if (AdminAccountInitializer.isWeakBootstrapPassword(trimmed)) {
            return "Password is too weak; use a strong, unique passphrase";
        }
        return null;
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