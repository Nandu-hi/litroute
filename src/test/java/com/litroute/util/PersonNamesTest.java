package com.litroute.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PersonNamesTest {

    private static void yes(String first, String last, String printed) {
        assertTrue(first + " " + last + " is " + printed, PersonNames.matches(first, last, printed));
    }

    private static void no(String first, String last, String printed) {
        assertFalse(first + " " + last + " is not " + printed, PersonNames.matches(first, last, printed));
    }

    @Test
    public void theFirstAndLastNameMustBothBePrinted() {
        yes("Alice", "Carter", "Alice Carter");
        yes("Alice", "Carter", "Alice K. Carter");
        yes("Alice", "Carter", "Alice K.Carter");
        yes("Alice", "Carter", "Alice Kate Carter");
        yes("Alice", "Carter", "Alice K. M. Carter");
        no("Alice", "Carter", "Alice");
        no("Alice", "Carter", "Carter");
    }

    @Test
    public void initialsAreNotAFirstName() {
        no("Alice", "Carter", "AK Carter");
        no("Alice", "Carter", "A. Carter");
        no("Alice", "Carter", "A Carter");
        no("Alice", "Carter", "A. K. Carter");
        no("Alice", "Carter", "A.K. Carter");
        no("Alice", "Carter", "Carter, A.");
        no("Alice", "Carter", "Carter AK");
    }

    @Test
    public void otherPeopleAreKeptApart() {
        no("Alice", "Carter", "Bob Carter");
        no("Alice", "Carter", "Alicia Carter");
        no("Alice", "Carter", "Alice Cartwright");
        no("Alice", "Carter", "K. Alice Carter");
        no("Alice", "Carter", "Carter Alice");
        no("Alice", "Carter", "Alice Carter Smith");
        no("Alice", "Carter", "");
    }

    @Test
    public void aCommaPutsTheLastNameFirst() {
        yes("Alice", "Carter", "Carter, Alice");
        yes("Alice", "Carter", "Carter, Alice K.");
        no("Alice", "Carter", "Alice, Carter");
    }

    @Test
    public void caseAndAccentsDoNotMatter() {
        yes("Alice", "Carter", "ALICE CARTER");
        yes("alice", "carter", "Alice K. Carter");
        yes("Moise", "Kapiri", "Moïse Musubao Kapiri");
        yes("Oguz", "Beyaztunc", "Oğuz Beyaztunç");
        yes("Søren", "Müller", "Soren Muller");
    }

    /** Punctuation inside a word doesn't change the name; a hyphenated name stays one name. */
    @Test
    public void aHyphenatedNameIsOneName() {
        yes("Zhan-Peng", "Gan", "Zhan-Peng Gan");
        yes("Zhan-Peng", "Gan", "Zhanpeng Gan");
        yes("Zhan Peng", "Gan", "Zhan-Peng Gan");
        yes("Zhanpeng", "Gan", "Zhan Peng Gan");
        yes("Sean", "O'Brien", "Sean O’Brien");
        yes("Santiago", "Londono-Lopez", "Santiago Londono‐Lopez");
        no("Jean", "Dupont", "Jean-Pierre Dupont");
        no("Pierre", "Dupont", "Jean-Pierre Dupont");
        no("Santiago", "Lopez", "Santiago Londono-Lopez");
    }

    @Test
    public void aLastNameOfSeveralWordsMustBeWhole() {
        yes("John", "van de Lindt", "John W. van de Lindt");
        yes("John", "van de Lindt", "van de Lindt, John W.");
        yes("Maria", "de la Cruz", "Maria de la Cruz");
        no("John", "van de Lindt", "John Lindt");
        yes("John", "Lindt", "John W. van de Lindt");
    }

    @Test
    public void theFirstAndLastNameMustNotOverlap() {
        no("Carter", "Carter", "Carter");
        yes("Carter", "Carter", "Carter Carter");
        no("Anna Maria", "Maria Lopez", "Anna Maria Lopez");
    }

    @Test
    public void anEmptyNameMatchesNothing() {
        no("", "Carter", "Carter");
        no("Alice", "...", "Alice");
    }

    @Test
    public void compactKeepsLettersAndDigitsAlone() {
        assertEquals("zhanpeng", PersonNames.compact("Zhan-Peng"));
        assertEquals("beyaztunc", PersonNames.compact("Beyaztunç"));
        assertEquals("k", PersonNames.compact("K."));
    }
}
