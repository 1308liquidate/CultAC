package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PalettedSectionDivisionTest {
    private static final int SECTION_SIZE = 4096;

    @Test
    void reciprocalShiftEqualsRemainderForEveryWidthAndIndex() {
        // setCode's shift from wordIndex, against the division it replaced, for every width a section can hold.
        for (int bits = 1; bits <= 32; bits++) {
            int perWord = 64 / bits;
            for (int index = 0; index < SECTION_SIZE; index++) {
                int word = PalettedSection.wordIndex(index, bits);
                assertEquals(index / perWord, word, bits + " " + index);
                assertEquals((index % perWord) * bits, (index - word * perWord) * bits, bits + " " + index);
            }
        }
    }

    @Test
    void sectionRoundTripsAcrossEveryGrowthWidth() {
        // Grows through the 4..8 bit local palettes into the global width, checking every
        // index after each write so a wrong word or shift shows up as a corrupted neighbour.
        PalettedSection section = PalettedSection.blocks();
        int[] expected = new int[SECTION_SIZE];
        int distinct = 300;
        for (int index = 0; index < SECTION_SIZE; index++) {
            int state = (index * 7919) % distinct;
            assertEquals(expected[index], section.getAndSet(index, state));
            expected[index] = state;
            if ((index & 255) == 255 || index < 2 * distinct) {
                for (int check = 0; check < SECTION_SIZE; check++) assertEquals(expected[check], section.get(check));
            }
        }
        PalettedSection copy = section.copy();
        for (int index = 0; index < SECTION_SIZE; index++) assertEquals(expected[index], copy.get(index));
    }
}
