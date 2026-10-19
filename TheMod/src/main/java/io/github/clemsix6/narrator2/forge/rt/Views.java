package io.github.clemsix6.narrator2.forge.rt;

import io.github.clemsix6.narrator2.forge.api.Limits;
import java.util.AbstractList;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * What {@link Guarded} hands a module in place of a view that grows something the module cannot measure through it:
 * a sub-list (adding through it grows the list it views, whatever its own size), a list iterator ({@code add}), and
 * the builders of streams (they hold elements without telling how many).
 */
final class Views {
    private static final int ELEMENTS = Limits.MAX_ELEMENTS;

    private Views() {}

    static <E> List<E> subList(List<E> list, int from, int to) {
        return new SubList<>(root(list), list.subList(from, to));
    }

    static <E> ListIterator<E> listIterator(List<E> list, ListIterator<E> cursor) {
        return new Cursor<>(root(list), cursor);
    }

    /** The list a sub-list's additions grow. */
    private static List<?> root(List<?> list) {
        return list instanceof SubList<?> view ? view.root : list;
    }

    static <T> Stream.Builder<T> builder() {
        Stream.Builder<T> builder = Stream.builder();
        return new Stream.Builder<>() {
            private int count;

            @Override
            public void accept(T element) {
                if (count >= ELEMENTS) throw Guarded.full();
                count++;
                builder.accept(element);
            }

            @Override
            public Stream<T> build() {
                return builder.build();
            }
        };
    }

    static IntStream.Builder intBuilder() {
        IntStream.Builder builder = IntStream.builder();
        return new IntStream.Builder() {
            private int count;

            @Override
            public void accept(int element) {
                if (count >= ELEMENTS) throw Guarded.full();
                count++;
                builder.accept(element);
            }

            @Override
            public IntStream build() {
                return builder.build();
            }
        };
    }

    static LongStream.Builder longBuilder() {
        LongStream.Builder builder = LongStream.builder();
        return new LongStream.Builder() {
            private int count;

            @Override
            public void accept(long element) {
                if (count >= ELEMENTS) throw Guarded.full();
                count++;
                builder.accept(element);
            }

            @Override
            public LongStream build() {
                return builder.build();
            }
        };
    }

    static DoubleStream.Builder doubleBuilder() {
        DoubleStream.Builder builder = DoubleStream.builder();
        return new DoubleStream.Builder() {
            private int count;

            @Override
            public void accept(double element) {
                if (count >= ELEMENTS) throw Guarded.full();
                count++;
                builder.accept(element);
            }

            @Override
            public DoubleStream build() {
                return builder.build();
            }
        };
    }

    /** A view of a list whose additions count against the list it views. */
    private static final class SubList<E> extends AbstractList<E> {
        final List<?> root;
        private final List<E> view;

        SubList(List<?> root, List<E> view) {
            this.root = root;
            this.view = view;
        }

        @Override
        public E get(int index) {
            return view.get(index);
        }

        @Override
        public E set(int index, E element) {
            return view.set(index, element);
        }

        @Override
        public int size() {
            return view.size();
        }

        @Override
        public void add(int index, E element) {
            if (root.size() >= ELEMENTS) throw Guarded.full();
            view.add(index, element);
            modCount++;
        }

        @Override
        public boolean addAll(Collection<? extends E> elements) {
            return addAll(size(), elements);
        }

        @Override
        public boolean addAll(int index, Collection<? extends E> elements) {
            if ((long) root.size() + elements.size() > ELEMENTS) throw Guarded.full();
            modCount++;
            return view.addAll(index, elements);
        }

        @Override
        public E remove(int index) {
            modCount++;
            return view.remove(index);
        }

        @Override
        public void clear() {
            modCount++;
            view.clear();
        }
    }

    /** A list iterator whose {@code add} counts against its list. */
    private static final class Cursor<E> implements ListIterator<E> {
        private final List<?> root;
        private final ListIterator<E> cursor;

        Cursor(List<?> root, ListIterator<E> cursor) {
            this.root = root;
            this.cursor = cursor;
        }

        @Override
        public boolean hasNext() {
            return cursor.hasNext();
        }

        @Override
        public E next() {
            return cursor.next();
        }

        @Override
        public boolean hasPrevious() {
            return cursor.hasPrevious();
        }

        @Override
        public E previous() {
            return cursor.previous();
        }

        @Override
        public int nextIndex() {
            return cursor.nextIndex();
        }

        @Override
        public int previousIndex() {
            return cursor.previousIndex();
        }

        @Override
        public void remove() {
            cursor.remove();
        }

        @Override
        public void set(E element) {
            cursor.set(element);
        }

        @Override
        public void add(E element) {
            if (root.size() >= ELEMENTS) throw Guarded.full();
            cursor.add(element);
        }
    }
}
