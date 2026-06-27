package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.config.HinataProperties;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin area: read and update runtime server settings (SSO, e-mail ingest,
 * push). Secured by the /api/v1/admin/** ADMIN rule in SecurityConfig.
 */
@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

	private final SettingsService settings;
	private final HinataProperties properties;
	private final AuditService audit;
	private final CurrentUser currentUser;

	@GetMapping
	public ServerSettings get() {
		ServerSettings current = settings.get();
		// Surface the effective app config (env defaults when not yet overridden)
		// so the admin form pre-fills the values currently served via /meta.
		ServerSettings.App app = current.getApp();
		HinataProperties.App defaults = properties.getApp();
		if (isBlank(app.getMinVersion())) {
			app.setMinVersion(defaults.getMinVersion());
		}
		if (isBlank(app.getPrivacyPolicyUrl())) {
			app.setPrivacyPolicyUrl(defaults.getPrivacyPolicyUrl());
		}
		if (isBlank(app.getIosStoreUrl())) {
			app.setIosStoreUrl(defaults.getIosStoreUrl());
		}
		if (isBlank(app.getAndroidStoreUrl())) {
			app.setAndroidStoreUrl(defaults.getAndroidStoreUrl());
		}
		if (isBlank(app.getMacosStoreUrl())) {
			app.setMacosStoreUrl(defaults.getMacosStoreUrl());
		}
		if (app.getFeatureFlags() == null || app.getFeatureFlags().isEmpty()) {
			app.setFeatureFlags(new java.util.LinkedHashMap<>(defaults.getFeatureFlags()));
		}
		return current;
	}

	@PutMapping
	public ServerSettings update(@RequestBody ServerSettings updated) {
		ServerSettings current = settings.get();
		// Setup completion and org identity are managed by the setup flow only.
		updated.setSetupCompleted(current.isSetupCompleted());
		if (isBlank(updated.getOrganizationName())) {
			updated.setOrganizationName(current.getOrganizationName());
		}
		keepSecretsIfBlank(updated, current);
		// Recorded before the save so that disabling audit logging itself is still
		// captured (the check reads the pre-save, still-enabled settings).
		audit.event(AuditAction.SETTINGS_CHANGED)
				.actor(currentUser.require())
				.meta("auditEnabled", String.valueOf(updated.getAudit().isEnabled()))
				.log();
		return settings.save(updated);
	}

	/** WRITE_ONLY secrets are not echoed back; keep stored values when omitted. */
	private void keepSecretsIfBlank(ServerSettings updated, ServerSettings current) {
		if (isBlank(updated.getOidc().getClientSecret())) {
			updated.getOidc().setClientSecret(current.getOidc().getClientSecret());
		}
		if (isBlank(updated.getOauth2().getClientSecret())) {
			updated.getOauth2().setClientSecret(current.getOauth2().getClientSecret());
		}
		if (isBlank(updated.getLdap().getManagerPassword())) {
			updated.getLdap().setManagerPassword(current.getLdap().getManagerPassword());
		}
		if (isBlank(updated.getEmailIngest().getPassword())) {
			updated.getEmailIngest().setPassword(current.getEmailIngest().getPassword());
		}
		if (isBlank(updated.getSmtp().getPassword())) {
			updated.getSmtp().setPassword(current.getSmtp().getPassword());
		}
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
