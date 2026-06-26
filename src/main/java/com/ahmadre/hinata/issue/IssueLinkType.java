package com.ahmadre.hinata.issue;

/**
 * Jira-style issue link types. Each link is stored once, directed from a
 * {@code source} (the "outward" side) to a {@code target} (the "inward" side);
 * the verb shown to the user depends on which end they are looking at.
 *
 * <p>Example: storing {@code BLOCKS, source=A, target=B} renders as
 * "A&nbsp;blocks&nbsp;B" on A and "B&nbsp;is&nbsp;blocked&nbsp;by&nbsp;A" on B.
 * {@link #RELATES} is symmetric — both ends read the same verb.
 */
public enum IssueLinkType {

	BLOCKS("blocks", "is blocked by"),
	CLONES("clones", "is cloned by"),
	CREATES("created", "created by"),
	DUPLICATES("duplicates", "is duplicated by"),
	RELATES("relates to", "relates to"),
	TESTS("tests", "is tested by"),
	SPLITS("split to", "split from");

	/** Verb shown on the source (outward) side, e.g. "blocks". */
	private final String outward;
	/** Verb shown on the target (inward) side, e.g. "is blocked by". */
	private final String inward;

	IssueLinkType(String outward, String inward) {
		this.outward = outward;
		this.inward = inward;
	}

	/** The verb to display from the perspective of the requested issue. */
	public String verb(boolean outward) {
		return outward ? this.outward : this.inward;
	}

	public String outwardVerb() {
		return outward;
	}

	public String inwardVerb() {
		return inward;
	}

	/** Symmetric links read the same on both ends and have no real direction. */
	public boolean isSymmetric() {
		return this == RELATES;
	}
}
