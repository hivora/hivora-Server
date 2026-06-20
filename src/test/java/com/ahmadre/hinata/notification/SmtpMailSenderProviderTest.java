package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmtpMailSenderProviderTest {

	@Test
	void buildsSenderFromEnabledAdminSettings() {
		ServerSettings settings = new ServerSettings();
		settings.getSmtp().setEnabled(true);
		settings.getSmtp().setHost("smtp.example.com");
		settings.getSmtp().setPort(587);
		settings.getSmtp().setUsername("mailer@example.com");
		settings.getSmtp().setPassword("secret");
		settings.getSmtp().setFromAddress("noreply@example.com");
		settings.getSmtp().setFromName("Example");
		SettingsService service = mock(SettingsService.class);
		when(service.get()).thenReturn(settings);

		SmtpMailSenderProvider provider = new SmtpMailSenderProvider(service);
		provider.init();

		assertThat(provider.sender()).isNotNull();
		assertThat(provider.fromAddress()).isEqualTo("noreply@example.com");
		assertThat(provider.fromName()).isEqualTo("Example");
	}

	@Test
	void noSenderWhenDisabledOrHostMissing() {
		SettingsService service = mock(SettingsService.class);
		when(service.get()).thenReturn(new ServerSettings()); // smtp disabled by default

		SmtpMailSenderProvider provider = new SmtpMailSenderProvider(service);
		provider.init();

		assertThat(provider.sender()).isNull();
		assertThat(provider.fromAddress()).isNull();
	}

	@Test
	void rebuildsLiveOnSettingsChange() {
		SettingsService service = mock(SettingsService.class);
		when(service.get()).thenReturn(new ServerSettings());
		SmtpMailSenderProvider provider = new SmtpMailSenderProvider(service);
		provider.init();
		assertThat(provider.sender()).isNull();

		ServerSettings updated = new ServerSettings();
		updated.getSmtp().setEnabled(true);
		updated.getSmtp().setHost("smtp.example.com");
		provider.onSettingsChanged(new SettingsService.SettingsChangedEvent(updated));

		assertThat(provider.sender()).isNotNull();
	}
}
