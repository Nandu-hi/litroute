package com.litroute.model;

import com.litroute.util.PersonNames;

/**
 * One entry on the professor's authors list: a first and a last name, spelled out. M6 favours the papers whose
 * author list holds this name, however many middle names or initials are printed in between; see
 * {@link PersonNames#matches}.
 *
 * @param firstName the author's first name, not an initial
 * @param lastName  the author's last name
 */
public record AuthorName(String firstName, String lastName) {

    /**
     * Input: a first and a last name, as typed.
     * Output: the name with its spacing tidied; throws if either part is blank or just an initial, which could never be told from someone else's.
     * Used by: AuthorsPane (M8), PriorityAuthorStore when loading.
     * Example: {@code new AuthorName(" Alice ", "Carter")} -> Alice Carter; {@code new AuthorName("A.", "Carter")} -> throws
     */
    public AuthorName {
        if (firstName == null || lastName == null) {
            throw new IllegalArgumentException("Enter both a first name and a last name.");
        }
        firstName = firstName.strip().replaceAll("\\s+", " ");
        lastName = lastName.strip().replaceAll("\\s+", " ");
        if (firstName.isEmpty() || lastName.isEmpty()) {
            throw new IllegalArgumentException("Enter both a first name and a last name.");
        }
        if (PersonNames.compact(firstName).length() < 2 || PersonNames.compact(lastName).length() < 2) {
            throw new IllegalArgumentException("Spell out both names: an initial can't identify an author.");
        }
    }

    /**
     * Input: a name printed on a paper.
     * Output: true if it is this author, strictly: see {@link PersonNames#matches}.
     * Used by: RelevanceScorer (M6), PriorityAuthors (M9).
     * Example: Alice Carter: "Alice K.Carter" -> true; "A. Carter" -> false
     */
    public boolean matches(String printed) {
        return PersonNames.matches(firstName, lastName, printed);
    }

    /**
     * Input: another entry.
     * Output: true if the two are the same first and last name, whatever the case or accents.
     * Used by: PriorityAuthors, AuthorsPane, to list a name once.
     * Example: Alice Carter vs alice CARTER -> true
     */
    public boolean sameAs(AuthorName other) {
        return PersonNames.compact(firstName).equals(PersonNames.compact(other.firstName))
                && PersonNames.compact(lastName).equals(PersonNames.compact(other.lastName));
    }

    /**
     * Input: none.
     * Output: the name as a person reads it.
     * Used by: the Authors tab's list and messages, M6's rationale.
     * Example: -> "Alice Carter"
     */
    @Override
    public String toString() {
        return firstName + " " + lastName;
    }
}
