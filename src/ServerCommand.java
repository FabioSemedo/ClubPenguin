public enum ServerCommand {
    NICK("/nick"),
    JOIN("/join"),
    LEAVE("/leave"),
    BYE("/bye"),
    PRIVATE("/priv");

    private final String label;

    ServerCommand(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }

    public static ServerCommand fromString(String label) {
        if (label == null)
            return null;
        for (ServerCommand c : ServerCommand.values()) {
            if (c.label.equals(label)) {
                return c;
            }
        }
        return null;
    }
}