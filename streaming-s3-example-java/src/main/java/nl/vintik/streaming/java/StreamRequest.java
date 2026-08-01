package nl.vintik.streaming.java;

/**
 * The minimal domain request produced by the {@code RequestParser} from the API Gateway
 * proxy event: the requested file name (1&ndash;1024 characters, enforced by the validator).
 *
 * @param fileName the raw file name lifted from {@code /pathParameters/proxy}
 */
public record StreamRequest(String fileName) {}
