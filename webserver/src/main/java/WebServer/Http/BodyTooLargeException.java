package WebServer.Http;

/** Raised when a request body exceeds {@link HttpCall#MAX_BODY_BYTES} (mapped to 413). */
public class BodyTooLargeException extends RuntimeException {
    public BodyTooLargeException() {
        super("body too large");
    }
}
