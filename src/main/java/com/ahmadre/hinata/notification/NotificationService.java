package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Fan-out for issue events: persists in-app notifications and sends e-mails.
 * Push (FCM) hooks in here once configured in the admin area.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

	private final NotificationRepository notifications;
	private final UserRepository users;
	private final MailService mail;
	private final PushService push;
	private final HinataProperties props;

	private static final String SUBJECT_PREFIX = "[Hinata] ";

	public void notifyIssueAssigned(Issue issue) {
		deliver(Set.of(issue.getAssigneeId()), Notification.Type.ISSUE_ASSIGNED,
				issue.getReadableId() + " assigned to you", issue.getTitle(), issueLink(issue));
	}

	public void notifyIssueUpdated(Issue issue, User editor, String change) {
		deliver(watchersWithout(issue, editor), Notification.Type.ISSUE_UPDATED,
				issue.getReadableId() + " updated", change, issueLink(issue));
	}

	/** Matches the inline mention token the editor emits: {@code {{user:<id>}}}. */
	private static final Pattern USER_MENTION = Pattern.compile("\\{\\{user:([^}]+)}}");

	/**
	 * Fan-out for a new comment. Users named with an {@code @}-mention get a
	 * direct {@code MENTION} notification; the issue's watchers get the broader
	 * {@code ISSUE_COMMENTED} notice. A mention supersedes the comment notice, so
	 * a mentioned watcher is not pinged twice. The comment author never notifies
	 * themselves.
	 */
	public void notifyComment(Issue issue, User author, String text) {
		Set<String> mentioned = parseUserMentions(text);
		mentioned.remove(author.getId());
		if (!mentioned.isEmpty()) {
			deliver(mentioned, Notification.Type.MENTION,
					author.getDisplayName() + " mentioned you in " + issue.getReadableId(),
					author.getDisplayName() + " mentioned you on \"" + issue.getTitle() + "\"",
					issueLink(issue));
		}
		Set<String> watchers = watchersWithout(issue, author);
		watchers.removeAll(mentioned);
		if (!watchers.isEmpty()) {
			deliver(watchers, Notification.Type.ISSUE_COMMENTED,
					"New comment on " + issue.getReadableId(),
					author.getDisplayName() + " commented on \"" + issue.getTitle() + "\"",
					issueLink(issue));
		}
	}

	/** Extracts the distinct user ids referenced by {@code {{user:<id>}}} tokens. */
	static Set<String> parseUserMentions(String text) {
		Set<String> ids = new HashSet<>();
		if (text == null) return ids;
		Matcher m = USER_MENTION.matcher(text);
		while (m.find()) {
			String id = m.group(1).trim();
			if (!id.isEmpty()) ids.add(id);
		}
		return ids;
	}

	// --- Team membership events ----------------------------------------------
	// Fan-out to the single affected user (in-app + e-mail), localized to their
	// own UI language. [teamName]/[teamId] are passed in so the caller need not
	// expose the Team type to this package.

	public void notifyAddedToTeam(String userId, String teamId, String teamName) {
		users.findById(userId).filter(User::isActive).ifPresent(user -> {
			String title = de(user) ? "Zu einem Team hinzugefügt" : "Added to a team";
			String body = de(user)
					? "Du wurdest dem Team \"" + teamName + "\" hinzugefügt."
					: "You've been added to the team \"" + teamName + "\".";
			deliverOne(user, Notification.Type.TEAM_ADDED, title, body, teamLink(teamId));
		});
	}

	public void notifyTeamRoleChanged(String userId, String teamId, String teamName, boolean admin) {
		users.findById(userId).filter(User::isActive).ifPresent(user -> {
			String title = de(user) ? "Team-Rolle aktualisiert" : "Team role updated";
			String body;
			if (de(user)) {
				body = admin
						? "Du bist jetzt Team-Admin von \"" + teamName + "\"."
						: "Deine Rolle in \"" + teamName + "\" ist jetzt Mitglied.";
			}
			else {
				body = admin
						? "You are now a Team-Admin of \"" + teamName + "\"."
						: "Your role in \"" + teamName + "\" is now Member.";
			}
			deliverOne(user, Notification.Type.TEAM_ROLE_CHANGED, title, body, teamLink(teamId));
		});
	}

	public void notifyRemovedFromTeam(String userId, String teamName) {
		users.findById(userId).filter(User::isActive).ifPresent(user -> {
			String title = de(user) ? "Aus einem Team entfernt" : "Removed from a team";
			String body = de(user)
					? "Du wurdest aus dem Team \"" + teamName + "\" entfernt."
					: "You've been removed from the team \"" + teamName + "\".";
			deliverOne(user, Notification.Type.TEAM_REMOVED, title, body, null);
		});
	}

	private void deliverOne(User user, Notification.Type type, String title, String body, String link) {
		notifications.save(Notification.builder()
				.userId(user.getId()).type(type).title(title).body(body).link(link).build());
		// In-app notifications keep the relative route; the e-mail button needs an
		// absolute deep link to the frontend so it works from any mail client.
		mail.send(user.getEmail(), SUBJECT_PREFIX + title, title, body, props.deepLink(link));
		push.sendToUser(user.getId(), title, body, link);
	}

	private String teamLink(String teamId) {
		return "/teams/" + teamId;
	}

	// --- Account lifecycle events ---------------------------------------------
	// These always reach the affected user by e-mail (even once deactivated or
	// deleted), so they bypass the active-user filter used for issue fan-out.

	public void notifyAccountActivated(User user) {
		String title = de(user) ? "Konto aktiviert" : "Account activated";
		String body = de(user)
				? "Dein Hinata-Konto wurde aktiviert. Du kannst dich jetzt wieder anmelden."
				: "Your Hinata account has been activated. You can sign in again now.";
		persist(user, Notification.Type.ACCOUNT_ACTIVATED, title, body, "/login");
		mail.sendTemplate(user.getEmail(), SUBJECT_PREFIX + title, "email/account-activated",
				accountModel(user, signInLink()));
	}

	public void notifyAccountDeactivated(User user) {
		String title = de(user) ? "Konto deaktiviert" : "Account deactivated";
		String body = de(user)
				? "Dein Hinata-Konto wurde deaktiviert. Du kannst dich derzeit nicht anmelden."
				: "Your Hinata account has been deactivated. You currently cannot sign in.";
		persist(user, Notification.Type.ACCOUNT_DEACTIVATED, title, body, null);
		mail.sendTemplate(user.getEmail(), SUBJECT_PREFIX + title, "email/account-deactivated",
				accountModel(user, null));
	}

	public void notifyRolesChanged(User user) {
		boolean isAdmin = user.isAdmin();
		String title = de(user) ? "Rollen aktualisiert" : "Roles updated";
		String body;
		if (de(user)) {
			body = isAdmin ? "Dir wurden Administrator-Rechte erteilt."
					: "Deine Administrator-Rechte wurden entfernt.";
		}
		else {
			body = isAdmin ? "You have been granted administrator privileges."
					: "Your administrator privileges have been removed.";
		}
		persist(user, Notification.Type.ACCOUNT_ROLE_CHANGED, title, body, null);
		Map<String, Object> model = accountModel(user, null);
		model.put("isAdmin", isAdmin);
		model.put("roles", roleLabels(user));
		mail.sendTemplate(user.getEmail(), SUBJECT_PREFIX + title, "email/account-role-changed", model);
	}

	/**
	 * Must be invoked <em>before</em> the user document is removed. No in-app
	 * notification is persisted because the account (and its notifications) are
	 * about to be deleted; the mail is dispatched asynchronously from captured
	 * values.
	 */
	public void notifyAccountDeleted(User user) {
		String title = de(user) ? "Konto gelöscht" : "Account deleted";
		mail.sendTemplate(user.getEmail(), SUBJECT_PREFIX + title, "email/account-deleted",
				accountModel(user, null));
	}

	private void persist(User user, Notification.Type type, String title, String body, String link) {
		notifications.save(Notification.builder()
				.userId(user.getId()).type(type).title(title).body(body).link(link).build());
		push.sendToUser(user.getId(), title, body, link);
	}

	private Map<String, Object> accountModel(User user, String ctaLink) {
		Map<String, Object> model = new HashMap<>();
		model.put("displayName", user.getDisplayName());
		model.put("locale", de(user) ? "de" : "en");
		model.put("ctaLink", ctaLink);
		return model;
	}

	private boolean de(User user) {
		return "de".equalsIgnoreCase(user.getLocale());
	}

	private String roleLabels(User user) {
		String member = de(user) ? "Mitglied" : "Member";
		return user.getRoles().stream()
				.sorted()
				.map(role -> role == Role.ADMIN ? "Administrator" : member)
				.collect(Collectors.joining(", "));
	}

	private String signInLink() {
		return props.deepLink("/login");
	}

	private Set<String> watchersWithout(Issue issue, User exclude) {
		Set<String> recipients = new HashSet<>(issue.getWatcherIds());
		if (issue.getAssigneeId() != null) recipients.add(issue.getAssigneeId());
		if (issue.getReporterId() != null) recipients.add(issue.getReporterId());
		if (exclude != null) recipients.remove(exclude.getId());
		return recipients;
	}

	private void deliver(Set<String> userIds, Notification.Type type, String title, String body,
			String link) {
		String eventId = eventId(type);
		for (String userId : userIds) {
			if (userId == null) continue;
			users.findById(userId).filter(User::isActive).ifPresent(user -> {
				// The in-app (bell) notification is always recorded; e-mail and push
				// are gated by the recipient's per-event channel preferences.
				notifications.save(Notification.builder()
						.userId(user.getId()).type(type).title(title).body(body).link(link).build());
				NotificationPreferences prefs = prefsOf(user);
				// In-app notifications keep the relative route; the e-mail button gets
				// an absolute deep link to the issue on the frontend.
				if (prefs.deliversEmail(eventId)) {
					mail.send(user.getEmail(), SUBJECT_PREFIX + title, title, body, props.deepLink(link));
				}
				if (prefs.deliversPush(eventId)) {
					push.sendToUser(user.getId(), title, body, link);
				}
			});
		}
	}

	/** Recipient's notification preferences, normalised (defaults for legacy users). */
	private NotificationPreferences prefsOf(User user) {
		NotificationPreferences prefs = user.getNotificationPreferences();
		return (prefs == null ? NotificationPreferences.defaults() : prefs).sanitized();
	}

	/**
	 * Maps a notification type to its preference event id (see
	 * {@link NotificationPreferences#EVENTS}). Transactional account/team/system
	 * events map to the locked {@code security} event, so they always deliver.
	 */
	private static String eventId(Notification.Type type) {
		return switch (type) {
			case MENTION -> "mentions";
			case ISSUE_ASSIGNED -> "assigned";
			case ISSUE_COMMENTED -> "comments";
			case ISSUE_UPDATED -> "status";
			case SPRINT_STARTED -> "sprint";
			default -> NotificationPreferences.LOCKED;
		};
	}

	private String issueLink(Issue issue) {
		return "/issues/" + issue.getReadableId();
	}
}
