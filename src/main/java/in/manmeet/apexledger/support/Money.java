package in.manmeet.apexledger.support;

/**
 * INR amount in paise. Never use float or double for money.
 */
public record Money(long minorUnits) {

    public static final String CURRENCY_INR = "INR";

    public Money {
        if (minorUnits < 0) {
            throw new IllegalArgumentException("Money cannot be negative: " + minorUnits);
        }
    }

    public static Money paise(long minorUnits) {
        return new Money(minorUnits);
    }
}
