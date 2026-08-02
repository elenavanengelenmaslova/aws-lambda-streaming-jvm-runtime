package nl.vintik.streaming.java;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the API Gateway proxy event off the Lambda {@link InputStream} into a
 * {@link StreamRequest}.
 *
 * <p>Only the requested file name is extracted, lifted from the {@code /{proxy+}} greedy
 * proxy integration's {@code /pathParameters/proxy} value; the rest of the
 * {@code aws-lambda-java-events} proxy-event shape is ignored. Emptiness, whitespace,
 * length, and character rules are NOT enforced here &mdash; that is the
 * {@code FileNameValidator}'s job, so an absent or null proxy value yields
 * {@code StreamRequest("")} for the validator to reject (Req 2.2).
 *
 * <p>Returns {@link ParseResult.ParseError} only when the event itself cannot be decoded
 * from the stream (malformed, empty, or truncated input), which the handler maps to
 * HTTP 400 (Req 2.3). A well-formed event with a missing proxy path parameter is a
 * successful {@link ParseResult.Parsed}, not an error (Req 2.1).
 *
 * <p>Java counterpart of the Kotlin example's {@code RequestParser}: it uses Jackson &mdash;
 * the idiomatic Java JSON reader &mdash; while the library still owns response serialization.
 */
public final class RequestParser {

    private static final Logger logger = LoggerFactory.getLogger(RequestParser.class);

    /**
     * JSON Pointer to the file name produced by the API Gateway {@code /{proxy+}} greedy
     * proxy integration: the requested file name arrives as {@code pathParameters.proxy}.
     */
    private static final String PROXY_POINTER = "/pathParameters/proxy";

    private final ObjectMapper mapper;

    /**
     * Wires a lenient {@link ObjectMapper} that tolerates the full proxy-event shape by
     * ignoring unknown properties, so only the fields we care about need to be read.
     */
    public RequestParser() {
        this(new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
    }

    /**
     * Package-private constructor allowing tests to inject a pre-configured mapper.
     *
     * @param mapper the JSON reader used to decode the proxy event tree
     */
    RequestParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Decodes the proxy event from {@code input} and lifts the requested file name.
     *
     * @param input the API Gateway proxy event on the Lambda input stream
     * @return {@link ParseResult.Parsed} carrying the (possibly empty) file name, or
     *     {@link ParseResult.ParseError} when the JSON cannot be decoded
     */
    public ParseResult parse(InputStream input) {
        try {
            JsonNode root = mapper.readTree(input);
            if (root == null || root.isMissingNode()) {
                // No content in the stream: treat as unparseable (Req 2.3).
                logger.warn("Could not parse API Gateway proxy event: no content in input stream");
                return new ParseResult.ParseError();
            }
            JsonNode proxy = root.at(PROXY_POINTER);
            String fileName = (proxy.isMissingNode() || proxy.isNull()) ? "" : proxy.asText();
            return new ParseResult.Parsed(new StreamRequest(fileName));
        } catch (IOException e) {
            logger.warn("Could not parse API Gateway proxy event from input stream", e);
            return new ParseResult.ParseError();
        }
    }
}
