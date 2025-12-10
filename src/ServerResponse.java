public enum ServerResponse {
    OK("OK"),
    ERROR("ERROR"),
    MESSAGE("MESSAGE"),
    NEWNICK("NEWNICK"),
    JOINED("JOINED"),
    LEFT("LEFT"),
    BYE("BYE"),
    PRIVATE("PRIVATE");

    private final String label;

    ServerResponse(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    public static ServerResponse fromString(String label) {
        if (label == null)
            return null;
        for (ServerResponse c : ServerResponse.values()) {
            if (c.label.equals(label)) {
                return c;
            }
        }
        return null;
    }
}