package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Creating, listing and removing the Jira-style links between issues
 * ({@link IssueLink}). All reads/writes are authorized against the caller's
 * project membership through {@link IssueService}, and every change is broadcast
 * to both ends over {@link IssueLinkEvents} for live updates.
 */
@Service
@RequiredArgsConstructor
public class IssueLinkService {

	private final IssueLinkRepository links;
	private final IssueService issues;
	private final IssueLinkEvents events;

	/**
	 * One link rendered from the perspective of the requested issue: the verb to
	 * show ("blocks" vs "is blocked by"), which way it points, and the issue on
	 * the other end.
	 */
	public record LinkView(String id, IssueLinkType type, boolean outward, String verb, Issue issue) {
	}

	/** All links touching {@code issueId}, oriented for that issue and ordered for display. */
	public List<LinkView> linksOf(String idOrReadableId, User user) {
		Issue issue = issues.getForUser(idOrReadableId, user);
		List<LinkView> views = new ArrayList<>();
		for (IssueLink link : links.findBySourceIdOrTargetId(issue.getId(), issue.getId())) {
			boolean outward = link.getSourceId().equals(issue.getId());
			String otherId = outward ? link.getTargetId() : link.getSourceId();
			Issue other = issues.findOrNull(otherId);
			// Skip dangling links and links to issues this user can't see (A01).
			if (other == null || !issues.canAccess(other, user)) continue;
			views.add(new LinkView(link.getId(), link.getType(), outward,
					link.getType().verb(outward), other));
		}
		// Group visually by verb, then by readable id within a group.
		views.sort(Comparator.comparing(LinkView::verb)
				.thenComparing(v -> v.issue().getReadableId(),
						Comparator.nullsLast(Comparator.naturalOrder())));
		return views;
	}

	/**
	 * Links {@code issueId} to each of {@code targetIds} with the given type and
	 * direction ({@code outward} = this issue is the source/verb subject). Targets
	 * the caller can't access, self-links and exact duplicates are skipped so a
	 * batch never half-fails. Returns the refreshed, oriented link list.
	 */
	public List<LinkView> addLinks(String idOrReadableId, IssueLinkType type, boolean outward,
			List<String> targetIds, User user) {
		if (type == null) throw ApiException.badRequest("error.issueLink.typeRequired");
		Issue issue = issues.getForUser(idOrReadableId, user);
		List<String> targets = targetIds == null ? List.of() : targetIds;
		for (String ref : targets) {
			if (ref == null || ref.isBlank()) continue;
			Issue other = issues.getForUser(ref.trim(), user); // 403/404 if not accessible
			if (Objects.equals(other.getId(), issue.getId())) {
				throw ApiException.badRequest("error.issueLink.self");
			}
			// Symmetric ("relates to") has no real direction; keep a canonical
			// orientation so the unique index actually dedupes a flipped pair.
			String sourceId;
			String targetId;
			if (type.isSymmetric()) {
				sourceId = issue.getId().compareTo(other.getId()) <= 0 ? issue.getId() : other.getId();
				targetId = sourceId.equals(issue.getId()) ? other.getId() : issue.getId();
			}
			else {
				sourceId = outward ? issue.getId() : other.getId();
				targetId = outward ? other.getId() : issue.getId();
			}
			if (links.findByTypeAndSourceIdAndTargetId(type, sourceId, targetId).isPresent()) {
				continue; // already linked — idempotent
			}
			try {
				links.save(IssueLink.builder()
						.type(type)
						.sourceId(sourceId)
						.targetId(targetId)
						.createdBy(user != null ? user.getId() : null)
						.build());
			}
			catch (org.springframework.dao.DuplicateKeyException race) {
				continue; // a concurrent identical link won — fine, it exists
			}
			events.publishChanged(issue.getId());
			events.publishChanged(other.getId());
		}
		return linksOf(issue.getId(), user);
	}

	/** Removes one link; the caller must be able to see one of its endpoints. */
	public List<LinkView> deleteLink(String idOrReadableId, String linkId, User user) {
		Issue issue = issues.getForUser(idOrReadableId, user);
		IssueLink link = links.findById(linkId)
				.orElseThrow(() -> ApiException.notFound("issueLink"));
		if (!issue.getId().equals(link.getSourceId()) && !issue.getId().equals(link.getTargetId())) {
			throw ApiException.notFound("issueLink"); // link doesn't belong to this issue
		}
		links.delete(link);
		events.publishChanged(link.getSourceId());
		events.publishChanged(link.getTargetId());
		return linksOf(issue.getId(), user);
	}
}
