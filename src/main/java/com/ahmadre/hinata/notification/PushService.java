package com.ahmadre.hinata.notification;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Sends push to a user's registered devices via Hinata Connect (the central
 * gateway that owns the published app's FCM credentials). Self-hosters need no
 * Firebase setup of their own. Invalid/expired tokens reported by the gateway
 * are pruned so the device collection self-heals.
 */
@Service
@RequiredArgsConstructor
public class PushService {

	private static final Logger log = LoggerFactory.getLogger(PushService.class);

	private final GatewayService gateway;
	private final DeviceTokenRepository devices;

	/**
	 * Fan a notification out to every device the user has registered. Runs
	 * asynchronously so the originating request (assign issue, comment, …) is
	 * never blocked on the network round-trip to the gateway.
	 */
	@Async
	public void sendToUser(String userId, String title, String body, String link) {
		if (userId == null) return;
		List<DeviceToken> tokens = devices.findByUserId(userId);
		for (DeviceToken device : tokens) {
			GatewayService.PushResult result = gateway.push(device.getToken(), title, body, link);
			if (result == GatewayService.PushResult.DEAD) {
				// Token will never deliver again (uninstalled / rotated / wrong sender):
				// drop it so the device collection self-heals and stops failing.
				devices.deleteByToken(device.getToken());
				log.debug("Pruned dead push token for user {}.", device.getUserId());
			}
		}
	}
}
