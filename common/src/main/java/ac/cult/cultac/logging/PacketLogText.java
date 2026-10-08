package ac.cult.cultac.logging;

public final class PacketLogText {
    private PacketLogText() {}

    public static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }
}
