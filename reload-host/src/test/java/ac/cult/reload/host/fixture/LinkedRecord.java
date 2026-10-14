package ac.cult.reload.host.fixture;

/** A record whose equals, hashCode and toString get linked by whichever class loader defines it. */
public record LinkedRecord(LinkedRecord.Part part, String name) {
    public interface Part {}

    public static String link() {
        Part part = new Part() {};
        var first = new LinkedRecord(part, "first");
        var second = new LinkedRecord(part, "second");
        return first.equals(second) + " " + first.hashCode() + " " + second;
    }
}
