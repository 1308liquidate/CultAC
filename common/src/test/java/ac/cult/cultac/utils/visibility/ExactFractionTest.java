package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ExactFractionTest {
    @Test
    void conversionRetainsExactBinaryCoordinatesIncludingSubnormals() {
        for (double value : new double[] {
            0,
            1,
            -1,
            .1,
            -.1,
            Double.MIN_VALUE,
            -Double.MIN_VALUE,
            Double.MIN_NORMAL,
            Double.MAX_VALUE,
            -30_000_000.000001
        }) {
            assertEquals(value, q(value).doubleValue());
        }
        assertTrue(q(.1).add(q(.2)).compareTo(q(.3)) > 0);
    }

    @Test
    void cancellationAndSignedDivisionKeepFractionsReduced() {
        assertEqual(q(.5), q(1).divide(q(3)).add(q(1).divide(q(6))));
        assertEqual(q(-.5), q(-1).divide(q(3)).subtract(q(1).divide(q(6))));
        assertEqual(ExactFraction.ONE, q(Math.scalb(1.0, 700)).multiply(q(Math.scalb(1.0, -700))));
        assertEqual(ExactFraction.ONE.negate(), q(2).divide(q(-2)));
        assertSame(ExactFraction.ZERO, q(7).subtract(q(7)));
        assertThrows(ArithmeticException.class, () -> q(1).divide(ExactFraction.ZERO));
    }

    @Test
    void operationsAgreeWithIndependentSmallIntegerArithmetic() {
        var random = new Random(0xf123);
        for (int i = 0; i < 1000; i++) {
            long a = random.nextInt(2049) - 1024, b = random.nextInt(1024) + 1;
            long c = random.nextInt(2049) - 1024, d = random.nextInt(1024) + 1;
            var left = fraction(a, b);
            var right = fraction(c, d);
            assertEqual(fraction(a * d + c * b, b * d), left.add(right));
            assertEqual(fraction(a * d - c * b, b * d), left.subtract(right));
            assertEqual(fraction(a * c, b * d), left.multiply(right));
            if (c != 0) assertEqual(fraction(a * d, b * c), left.divide(right));
        }
    }

    @Test
    void roundedBoundsEncloseExactResultsAfterCancellationAndLargeArithmetic() {
        var random = new Random(0xe1105e);
        for (int i = 0; i < 1000; i++) {
            var value = fraction(random.nextInt(2049) - 1024, random.nextInt(1024) + 1)
                    .multiply(q(Math.scalb(1.0, random.nextInt(2001) - 1000)))
                    .add(fraction(random.nextInt(2049) - 1024, random.nextInt(1024) + 1));
            if (Double.isFinite(value.lowerBound()))
                assertTrue(q(value.lowerBound()).compareTo(value) <= 0);
            if (Double.isFinite(value.upperBound()))
                assertTrue(q(value.upperBound()).compareTo(value) >= 0);
        }
    }

    private static ExactFraction fraction(long numerator, long denominator) {
        return q(numerator).divide(q(denominator));
    }

    private static ExactFraction q(double value) {
        return ExactFraction.of(value);
    }

    private static void assertEqual(ExactFraction expected, ExactFraction actual) {
        assertEquals(0, expected.compareTo(actual));
    }
}
