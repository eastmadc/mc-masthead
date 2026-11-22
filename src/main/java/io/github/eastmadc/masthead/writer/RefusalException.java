package io.github.eastmadc.masthead.writer;

/** The model declined (stop_reason "refusal"). {@link #category()} is the API's stop_details.category, or "unknown". */
public final class RefusalException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String category;

    public RefusalException(String category) {
        super("the model declined to write this (category: " + category + ")");
        this.category = category;
    }

    public String category() {
        return category;
    }
}
