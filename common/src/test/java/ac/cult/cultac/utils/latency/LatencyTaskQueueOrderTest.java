package ac.cult.cultac.utils.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * LatencyTaskQueue runs tasks in the order of the single-list scan it replaces. Random scripts drive both: tasks at
 * past, present, future and extreme transactions, continuations, drains that skip, repeat or go backwards, tasks that
 * enqueue or drain again while their batch runs, and tasks that throw part way through a batch. The ids run, in
 * order, must match after every drain.
 */
class LatencyTaskQueueOrderTest {
    private int runsBeyondFirst;
    private int overflowUsed;

    @Test
    void tasksRunInTheScanOrder() throws Exception {
        for (long seed = 0; seed < 600; seed++) compare(seed, 2_500);
        assertTrue(runsBeyondFirst > 0, "no task ever needed a second run");
        assertTrue(overflowUsed > 0, "no task ever went to the overflow");
    }

    @Test
    void movementLikeTrafficRunsInTheScanOrder() throws Exception {
        for (long seed = 0; seed < 200; seed++) traffic(seed, 4_000);
    }

    private void compare(long seed, int steps) throws Exception {
        Random random = new Random(seed);
        Driver legacy = new Driver(new LegacyQueue(), seed);
        CurrentQueue current = new CurrentQueue();
        Driver candidate = new Driver(current, seed);
        for (int step = 0; step < steps; step++) {
            long op = random.nextLong();
            legacy.apply(op);
            candidate.apply(op);
            observe(current.queue);
            assertEquals(legacy.log, candidate.log, "seed=" + seed + " step=" + step);
        }
        candidate.queue.drain(Integer.MAX_VALUE, candidate);
        legacy.queue.drain(Integer.MAX_VALUE, legacy);
        assertEquals(legacy.log, candidate.log, "seed=" + seed + " final");
    }

    /** Pongs in order, movement tasks at the next transaction and a later proof, one task in three at T+1 late. */
    private void traffic(long seed, int steps) {
        List<List<Integer>> logs = new ArrayList<>();
        for (Queue queue : List.of(new LegacyQueue(), new CurrentQueue())) {
            Random script = new Random(seed);
            List<Integer> log = new ArrayList<>();
            int sent = 0;
            int received = 0;
            int id = 0;
            for (int step = 0; step < steps; step++) {
                int kind = script.nextInt(10);
                if (kind < 3) {
                    sent++;
                } else if (kind < 7) {
                    int task = id++;
                    int at = sent + 1 + script.nextInt(2);
                    if (script.nextInt(3) == 0) queue.add(sent + 1, () -> log.add(task));
                    else if (script.nextBoolean()) queue.addNext(at, () -> log.add(task), () -> log.add(-task));
                    else queue.add(at, () -> log.add(task));
                } else if (received < sent) {
                    received += 1 + script.nextInt(Math.min(3, sent - received));
                    queue.drain(received, null);
                }
            }
            queue.drain(Integer.MAX_VALUE, null);
            logs.add(log);
        }
        assertEquals(logs.get(0), logs.get(1), "seed=" + seed);
    }

    private void observe(LatencyTaskQueue queue) throws Exception {
        Field runs = LatencyTaskQueue.class.getDeclaredField("runFirst");
        runs.setAccessible(true);
        Object[] first = (Object[]) runs.get(queue);
        for (int run = 1; run < first.length; run++) if (first[run] != null) runsBeyondFirst++;
        Field overflow = LatencyTaskQueue.class.getDeclaredField("firstTask");
        overflow.setAccessible(true);
        if (overflow.get(queue) != null) overflowUsed++;
    }

    private interface Queue {
        void add(int transaction, Runnable task);

        void addNext(int transaction, Runnable task, Runnable next);

        /** Drains and runs the batch as LatencyUtils does; a throwing task ends the batch, the rest is dropped. */
        void drain(int transaction, Driver driver);
    }

    /** One queue driven by the shared script; task behaviour depends only on the seed and the task id. */
    private static final class Driver {
        final Queue queue;
        final long seed;
        final List<Integer> log = new ArrayList<>();
        int sent;
        int received;
        int nextId;
        int depth;

        Driver(Queue queue, long seed) {
            this.queue = queue;
            this.seed = seed;
        }

        void apply(long op) {
            Random random = new Random(op);
            int kind = random.nextInt(100);
            if (kind < 40) {
                enqueue(random, sent);
            } else if (kind < 52) {
                sent += 1 + random.nextInt(2);
            } else {
                int transaction = switch (random.nextInt(20)) {
                    case 0 -> received - random.nextInt(4);
                    case 1 -> Integer.MAX_VALUE;
                    case 2 -> received + 5 + random.nextInt(10);
                    default -> received + random.nextInt(4);
                };
                received = Math.max(received, transaction == Integer.MAX_VALUE ? received : transaction);
                queue.drain(transaction, this);
            }
        }

        void enqueue(Random random, int base) {
            int transaction = switch (random.nextInt(25)) {
                case 0 -> Integer.MAX_VALUE;
                case 1 -> Integer.MAX_VALUE - 1;
                case 2 -> Integer.MIN_VALUE;
                case 3 -> base + 20 + random.nextInt(40);
                case 4, 5, 6 -> base - random.nextInt(6);
                default -> base + random.nextInt(4);
            };
            int id = nextId++;
            if (random.nextInt(3) == 0) {
                queue.addNext(transaction, task(id), () -> log.add(-id - 1));
            } else {
                queue.add(transaction, task(id));
            }
        }

        private Runnable task(int id) {
            return () -> {
                log.add(id);
                Random random = new Random(seed * 1_000_003L + id);
                int roll = random.nextInt(100);
                if (roll < 2) throw new IllegalStateException("abort " + id);
                if (roll < 22) {
                    int count = 1 + random.nextInt(3);
                    for (int i = 0; i < count; i++) enqueue(random, received + random.nextInt(3) - 1);
                } else if (roll < 26 && depth < 3) {
                    depth++;
                    try {
                        queue.drain(received + random.nextInt(2), this);
                    } finally {
                        depth--;
                    }
                }
            };
        }
    }

    private static final class CurrentQueue implements Queue {
        final LatencyTaskQueue queue = new LatencyTaskQueue();

        @Override
        public void add(int transaction, Runnable task) {
            queue.add(transaction, task);
        }

        @Override
        public void addNext(int transaction, Runnable task, Runnable next) {
            queue.addWithNextTransaction(transaction, task, next);
        }

        @Override
        public void drain(int transaction, Driver driver) {
            var node = queue.drainReady(transaction);
            LatencyTaskQueue.TaskNode released = null;
            try {
                while (node != null) {
                    var next = node.next();
                    var task = node.runnable();
                    released = node.clearAndLink(released);
                    task.run();
                    node = next;
                }
            } catch (IllegalStateException aborted) {
                if (driver != null) driver.log.add(Integer.MIN_VALUE);
            } finally {
                queue.recycle(released);
            }
        }
    }

    private static final class LegacyQueue implements Queue {
        final Legacy queue = new Legacy();

        @Override
        public void add(int transaction, Runnable task) {
            queue.add(transaction, task);
        }

        @Override
        public void addNext(int transaction, Runnable task, Runnable next) {
            queue.addWithNextTransaction(transaction, task, next);
        }

        @Override
        public void drain(int transaction, Driver driver) {
            var node = queue.drainReady(transaction);
            Legacy.TaskNode released = null;
            try {
                while (node != null) {
                    var next = node.next();
                    var task = node.runnable();
                    released = node.clearAndLink(released);
                    task.run();
                    node = next;
                }
            } catch (IllegalStateException aborted) {
                if (driver != null) driver.log.add(Integer.MIN_VALUE);
            } finally {
                queue.recycle(released);
            }
        }
    }

    /** LatencyTaskQueue before the runs, verbatim. */
    private static final class Legacy {
        private static final int MAX_RECYCLED_TASKS = 256;
        private TaskNode firstTask;
        private TaskNode lastTask;
        private TaskNode recycledTasks;
        private int recycledTaskCount;

        synchronized void add(int transaction, Runnable runnable) {
            add(acquire(transaction, runnable, null));
        }

        synchronized void addWithNextTransaction(int transaction, Runnable runnable, Runnable nextTransactionRunnable) {
            add(acquire(transaction, runnable, nextTransactionRunnable));
        }

        private TaskNode acquire(int transaction, Runnable runnable, Runnable nextTransactionRunnable) {
            TaskNode task = recycledTasks;
            if (task == null) return new TaskNode(transaction, runnable, nextTransactionRunnable);
            recycledTasks = task.next;
            recycledTaskCount--;
            task.transaction = transaction;
            task.runnable = runnable;
            task.nextTransactionRunnable = nextTransactionRunnable;
            task.next = null;
            return task;
        }

        // The caller must finish walking the drained batch before returning any of its nodes.
        synchronized void recycle(TaskNode task) {
            if (task == null || recycledTaskCount == MAX_RECYCLED_TASKS) return;
            TaskNode tail = task;
            int count = 1;
            while (recycledTaskCount + count < MAX_RECYCLED_TASKS && tail.next != null) {
                tail = tail.next;
                count++;
            }
            tail.next = recycledTasks;
            recycledTasks = task;
            recycledTaskCount += count;
        }

        private TaskNode nextTransactionTask(TaskNode task) {
            if (task.nextTransactionRunnable == null) return null;
            TaskNode continuation = acquire(task.transaction + 1, task.nextTransactionRunnable, null);
            task.nextTransactionRunnable = null;
            return continuation;
        }

        private void add(TaskNode task) {
            if (lastTask == null) {
                firstTask = task;
            } else {
                lastTask.next = task;
            }
            lastTask = task;
        }

        synchronized TaskNode drainReady(int transaction) {
            TaskNode previous = null;
            TaskNode current = firstTask;
            TaskNode readyFirst = null;
            TaskNode readyLast = null;

            while (current != null) {
                TaskNode next = current.next;
                // Receipt callbacks can append earlier transactions behind later ones.
                // Keep scanning so future tasks cannot hide ready work.
                if (transaction < current.transaction) {
                    previous = current;
                    current = next;
                    continue;
                }

                TaskNode continuation = nextTransactionTask(current);
                if (continuation != null) {
                    if (continuation.transaction <= transaction) {
                        if (previous == null) {
                            firstTask = next;
                        } else {
                            previous.next = next;
                        }
                        if (lastTask == current) {
                            lastTask = previous;
                        }

                        current.next = continuation;
                        if (readyFirst == null) {
                            readyFirst = current;
                        } else {
                            readyLast.next = current;
                        }
                        readyLast = continuation;
                        current = next;
                        continue;
                    }

                    continuation.next = next;
                    if (previous == null) {
                        firstTask = continuation;
                    } else {
                        previous.next = continuation;
                    }
                    if (lastTask == current) {
                        lastTask = continuation;
                    }

                    current.next = null;
                    if (readyFirst == null) {
                        readyFirst = current;
                    } else {
                        readyLast.next = current;
                    }
                    readyLast = current;
                    previous = continuation;
                    current = next;
                    continue;
                }

                if (previous == null) {
                    firstTask = next;
                } else {
                    previous.next = next;
                }
                if (lastTask == current) {
                    lastTask = previous;
                }

                current.next = null;
                if (readyFirst == null) {
                    readyFirst = current;
                } else {
                    readyLast.next = current;
                }
                readyLast = current;
                current = next;
            }

            if (firstTask == null) {
                lastTask = null;
            }
            return readyFirst;
        }

        static final class TaskNode {
            private int transaction;
            private Runnable runnable;
            private Runnable nextTransactionRunnable;
            private TaskNode next;

            private TaskNode(int transaction, Runnable runnable, Runnable nextTransactionRunnable) {
                this.transaction = transaction;
                this.runnable = runnable;
                this.nextTransactionRunnable = nextTransactionRunnable;
            }

            Runnable runnable() {
                return runnable;
            }

            TaskNode next() {
                return next;
            }

            void clear() {
                runnable = null;
                nextTransactionRunnable = null;
                next = null;
            }

            TaskNode clearAndLink(TaskNode released) {
                clear();
                next = released;
                return this;
            }
        }
    }
}
