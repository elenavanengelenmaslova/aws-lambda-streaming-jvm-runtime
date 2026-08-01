package nl.vintik.streaming.java;

/**
 * Typed request for a file-serving endpoint: carries the validated file name that is
 * used to address the object in S3.
 *
 * <p>Java counterpart of the Kotlin example's {@code FileRequest}. Consumed by
 * {@code S3Source} when confirming existence/size and opening the object body.
 *
 * @param fileName the validated S3 object key requested by the client
 */
public record FileRequest(String fileName) {}
