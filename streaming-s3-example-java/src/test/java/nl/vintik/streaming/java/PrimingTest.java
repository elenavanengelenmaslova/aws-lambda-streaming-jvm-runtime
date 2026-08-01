package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import nl.vintik.lambda.streaming.ResponseWriter;
import org.crac.Context;
import org.crac.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the CRaC {@link Priming} hook, driven through its package-private four-factory
 * constructor so no real snapshot resource is registered with the global CRaC context and no real
 * AWS collaborators are built.
 *
 * <p>Two behaviours are asserted:
 * <ul>
 *   <li><b>Single pass over the critical path (Req 8.2):</b> one {@link Priming#beforeCheckpoint}
 *       call touches, exactly once and in order, the three warm-up paths &mdash; S3 client
 *       initialization (the {@link S3Source} factory), one {@link StreamHandler#handleRequest}
 *       invocation against the primed request, and response metadata serialization through the
 *       library's {@link ResponseWriter#writeMetadata}. Each collaborator factory is invoked
 *       exactly once.</li>
 *   <li><b>No error swallowing (Req 8.3):</b> a failure on any primed path propagates out of
 *       {@code beforeCheckpoint} so snapshot creation fails and no Lambda version is published.
 *       Verified for both a throwing handler invocation and a throwing metadata serialization.</li>
 * </ul>
 *
 * <p>The collaborators ({@link S3Source}, {@link StreamHandler}, {@link ResponseWriter}) are
 * {@code final}; Mockito's inline mock maker (enabled repo-wide via
 * {@code net.bytebuddy.experimental=true}) mocks them. Each factory is a counting {@link Supplier}
 * so its invocation count can be asserted, and the handler/writer interactions record their call
 * order to prove the single, ordered pass. A no-op Mockito {@link Context} backs the checkpoint
 * call; {@code beforeCheckpoint} never reads it.
 *
 * <p>Requirements: 8.2, 8.3
 */
class PrimingTest {

    /** Records the ordered sequence of critical-path touches within one {@code beforeCheckpoint}. */
    private final List<String> callOrder = new ArrayList<>();

    private final AtomicInteger s3FactoryCount = new AtomicInteger();
    private final AtomicInteger handlerFactoryCount = new AtomicInteger();
    private final AtomicInteger writerFactoryCount = new AtomicInteger();
    private final AtomicInteger requestFactoryCount = new AtomicInteger();

    private S3Source s3Source;
    private StreamHandler handler;
    private ResponseWriter writer;
    private InputStream primedRequest;

    private Priming priming;

    @BeforeEach
    void setUp() {
        s3Source = mock(S3Source.class);
        handler = mock(StreamHandler.class);
        writer = mock(ResponseWriter.class);
        // A distinct, identifiable stream instance so the handler can be verified to receive
        // exactly the stream the primed-request factory produced.
        primedRequest = new ByteArrayInputStream(new byte[0]);

        // Counting factories: the S3 factory records "s3-init" because client initialization is
        // the S3 warm-up path (it happens in S3Source construction); the handler/writer factories
        // only count, while their record()-ed method invocations mark the actual critical paths.
        Supplier<S3Source> s3Factory = () -> {
            s3FactoryCount.incrementAndGet();
            callOrder.add("s3-init");
            return s3Source;
        };
        Supplier<StreamHandler> handlerFactory = () -> {
            handlerFactoryCount.incrementAndGet();
            return handler;
        };
        Supplier<ResponseWriter> writerFactory = () -> {
            writerFactoryCount.incrementAndGet();
            return writer;
        };
        Supplier<InputStream> requestFactory = () -> {
            requestFactoryCount.incrementAndGet();
            return primedRequest;
        };

        priming = new Priming(s3Factory, handlerFactory, writerFactory, requestFactory);
    }

    @Test
    @DisplayName("Given the primed collaborators When beforeCheckpoint Then S3 init, one handler invocation, and metadata serialization are each touched once, in order")
    void beforeCheckpointExercisesEveryCriticalPathOnceInASinglePass() throws IOException {
        // Record the two method-driven critical paths as they happen so the ordered, single pass
        // can be asserted alongside the S3-init marker from the factory.
        doAnswer(invocation -> {
            callOrder.add("handler-invoke");
            return null;
        }).when(handler).handleRequest(any(), any(), any());
        doAnswer(invocation -> {
            callOrder.add("metadata-serialize");
            return null;
        }).when(writer).writeMetadata(any(), any());

        priming.beforeCheckpoint(noOpContext());

        // Each collaborator factory is invoked exactly once (single pass).
        assertEquals(1, s3FactoryCount.get(), "the S3 source factory must be invoked exactly once");
        assertEquals(1, handlerFactoryCount.get(), "the handler factory must be invoked exactly once");
        assertEquals(1, writerFactoryCount.get(), "the response writer factory must be invoked exactly once");
        assertEquals(1, requestFactoryCount.get(), "the primed-request factory must be invoked exactly once");

        // The handler is invoked exactly once, against the exact stream the primed-request factory
        // produced, and the writer serializes metadata exactly once.
        verify(handler, times(1)).handleRequest(same(primedRequest), any(), any());
        verify(writer, times(1)).writeMetadata(any(), any());

        // Warming the S3 path only requires constructing the source (client init in its ctor); no
        // method is invoked on it during priming.
        verifyNoInteractions(s3Source);

        // The three critical paths are touched exactly once, in the required order, in one pass.
        assertEquals(List.of("s3-init", "handler-invoke", "metadata-serialize"), callOrder);
    }

    @Test
    @DisplayName("Given the primed handler invocation throws When beforeCheckpoint Then the error propagates and metadata is never serialized")
    void beforeCheckpointPropagatesWhenPrimedHandlerThrows() throws IOException {
        // A failure on the primed handler path must fail the snapshot — beforeCheckpoint must not
        // swallow it (Req 8.3).
        IOException primedFailure = new IOException("primed handler invocation failed");
        doThrow(primedFailure).when(handler).handleRequest(any(), any(), any());

        IOException thrown = assertThrows(
                IOException.class,
                () -> priming.beforeCheckpoint(noOpContext()));

        assertSame(primedFailure, thrown, "the primed handler failure must propagate unchanged");
        // The failure aborts the pass before serialization: no metadata is written and the error
        // is not swallowed into a later step.
        verify(writer, never()).writeMetadata(any(), any());
    }

    @Test
    @DisplayName("Given metadata serialization throws When beforeCheckpoint Then the error propagates")
    void beforeCheckpointPropagatesWhenMetadataSerializationThrows() throws IOException {
        // The primed handler invocation succeeds; the final serialization path fails. That failure
        // must also propagate so the snapshot fails (Req 8.3).
        RuntimeException primedFailure = new RuntimeException("metadata serialization failed");
        doThrow(primedFailure).when(writer).writeMetadata(any(), any());

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> priming.beforeCheckpoint(noOpContext()));

        assertSame(primedFailure, thrown, "the serialization failure must propagate unchanged");
        // The handler invocation (step 2) still ran exactly once before serialization (step 3) failed.
        verify(handler, times(1)).handleRequest(any(), any(), any());
    }

    /** A no-op CRaC {@link Context} for the checkpoint call; {@code beforeCheckpoint} never reads it. */
    @SuppressWarnings("unchecked")
    private static Context<? extends Resource> noOpContext() {
        return mock(Context.class);
    }
}
