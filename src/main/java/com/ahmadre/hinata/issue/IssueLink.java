package com.ahmadre.hinata.issue;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A directed link between two issues (e.g. "blocks", "duplicates", "relates
 * to"). Stored exactly once — the reciprocal verb is derived per side from
 * {@link IssueLinkType} — so the two ends can never diverge. The unique index
 * makes adding the same link twice a no-op.
 */
@Data
@Builder
@Document("issue_links")
@CompoundIndex(name = "link_unique", def = "{'type': 1, 'sourceId': 1, 'targetId': 1}", unique = true)
public class IssueLink {

	@Id
	private String id;

	private IssueLinkType type;

	/** The issue on the outward side of the verb (e.g. the one that "blocks"). */
	@Indexed
	private String sourceId;

	/** The issue on the inward side of the verb (e.g. the one "blocked by"). */
	@Indexed
	private String targetId;

	/** User who created the link; null for seeded/system links. */
	private String createdBy;

	@CreatedDate
	private Instant createdAt;
}
