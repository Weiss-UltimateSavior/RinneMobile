package com.apps.agent;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class AgentMutationTransactionTest {
    @Test public void partialWriteFailureRestoresAndVerifiesOriginal() {
        FakeDocument document = new FakeDocument("before".getBytes());
        document.failFirstWriteAfterPartial = true;
        AgentMutationTransaction.Failure failure = assertThrows(AgentMutationTransaction.Failure.class,
                () -> AgentMutationTransaction.replace(document, "before".getBytes(), "after".getBytes(), () -> true, () -> { }));
        assertTrue(failure.restored);
        assertArrayEquals("before".getBytes(), document.value);
    }

    @Test public void reportsWhenPartialWriteAndRollbackBothFail() {
        FakeDocument document = new FakeDocument("before".getBytes());
        document.failEveryWrite = true;
        AgentMutationTransaction.Failure failure = assertThrows(AgentMutationTransaction.Failure.class,
                () -> AgentMutationTransaction.replace(document, "before".getBytes(), "after".getBytes(), () -> true, () -> { }));
        assertFalse(failure.restored);
    }

    @Test public void concurrentChangeBeforeWriteIsNeverOverwritten() {
        FakeDocument document = new FakeDocument("changed".getBytes());
        assertThrows(IOException.class, () -> AgentMutationTransaction.replace(
                document, "before".getBytes(), "after".getBytes(), () -> true, () -> { }));
        assertArrayEquals("changed".getBytes(), document.value);
    }

    @Test public void cancellationAfterWriteRollsBack() {
        FakeDocument document = new FakeDocument("before".getBytes());
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        AgentMutationTransaction.Failure failure = assertThrows(AgentMutationTransaction.Failure.class,
                () -> AgentMutationTransaction.replace(document, "before".getBytes(), "after".getBytes(),
                        () -> checks.incrementAndGet() == 1, () -> { }));
        assertTrue(failure.restored);
        assertArrayEquals("before".getBytes(), document.value);
    }

    @Test public void commitCallbackFailureRollsBack() {
        FakeDocument document = new FakeDocument("before".getBytes());
        AgentMutationTransaction.Failure failure = assertThrows(AgentMutationTransaction.Failure.class,
                () -> AgentMutationTransaction.replace(document, "before".getBytes(), "after".getBytes(),
                        () -> true, () -> { throw new IOException("journal failed"); }));
        assertTrue(failure.restored);
    }

    @Test public void successfulCommitCallbackRunsExactlyOnce() throws Exception {
        FakeDocument document = new FakeDocument("before".getBytes());
        java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();
        AgentMutationTransaction.replace(document, "before".getBytes(), "after".getBytes(),
                () -> true, commits::incrementAndGet);
        assertEquals(1, commits.get());
        assertArrayEquals("after".getBytes(), document.value);
    }

    private static final class FakeDocument implements AgentMutationTransaction.DocumentIo {
        byte[] value;
        boolean failFirstWriteAfterPartial;
        boolean failEveryWrite;
        int writes;
        FakeDocument(byte[] value) { this.value = value; }
        @Override public byte[] read() { return value.clone(); }
        @Override public void write(byte[] next) throws Exception {
            writes++;
            if (failEveryWrite || (failFirstWriteAfterPartial && writes == 1)) {
                value = new byte[]{next[0]};
                throw new IOException("provider failed after truncation");
            }
            value = next.clone();
        }
    }
}
