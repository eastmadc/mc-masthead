package io.github.eastmadc.masthead.collect;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * PT-R2-3: the main thread's 30 s hand-off never waits for a background write that is stuck on the disk. The Recorder
 * is made without its constructor (which needs a running server); only the inflight list is set.
 */
public final class RecorderLockTest {
    public static void main(String[] args) throws Exception {
        Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Object unsafe = uf.get(null);
        Recorder r = (Recorder) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Recorder.class);
        Field inf = Recorder.class.getDeclaredField("inflight");
        inf.setAccessible(true);
        inf.set(r, new ArrayList<List<String[]>>());

        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread writer = new Thread(() -> {   // a background write stalled on the disk holds the Recorder's lock
            synchronized (r) {
                held.countDown();
                try { release.await(); } catch (InterruptedException ignored) { }
            }
        });
        writer.setDaemon(true);
        writer.start();
        held.await();
        CountDownLatch done = new CountDownLatch(1);
        Thread main = new Thread(() -> { r.handOff(new ArrayList<>()); done.countDown(); });
        main.setDaemon(true);
        main.start();
        boolean quick = done.await(1, TimeUnit.SECONDS);
        release.countDown();
        if (!quick) throw new AssertionError("PT-R2-3: the hand-off waited for a stalled background write");
        if (((List<?>) inf.get(r)).size() != 1) throw new AssertionError("PT-R2-3: the batch was not handed off");
        System.out.println("RecorderLockTest: ok");
    }
}
