package net.abled.medieval.core.catalogue;

/**
 * One page of a listing, expressed as a range into the underlying entries.
 *
 * <p>Pure arithmetic over a size, so paging is unit-tested without a server. Two decisions are
 * deliberate:
 *
 * <ul>
 *   <li>The requested page is <em>clamped</em>, never rejected. A menu can be open while the
 *       listing behind it shrinks - a reload, a different category, a shorter last page - and a
 *       stale page number must render the nearest valid page instead of throwing inside an
 *       inventory click handler.</li>
 *   <li>An empty listing still reports one page. Callers can then draw an empty page, and no code
 *       has to guard against a page count of zero before dividing by it.</li>
 * </ul>
 *
 * @param index         zero-based page number, always within {@code [0, pageCount)}
 * @param pageCount     number of pages, at least one
 * @param fromInclusive index of the first entry on this page, equal to the total when empty
 * @param toExclusive   index one past the last entry on this page
 */
public record CataloguePage(int index, int pageCount, int fromInclusive, int toExclusive) {

    public CataloguePage {
        if (pageCount < 1) {
            throw new IllegalArgumentException("pageCount must be at least 1: " + pageCount);
        }
        if (index < 0 || index >= pageCount) {
            throw new IllegalArgumentException("page index " + index + " is outside 0.." + (pageCount - 1));
        }
        if (fromInclusive < 0 || toExclusive < fromInclusive) {
            throw new IllegalArgumentException("invalid range " + fromInclusive + ".." + toExclusive);
        }
    }

    /**
     * @param entryCount     how many entries the listing holds; must not be negative
     * @param pageSize       how many entries fit on one page; must be positive
     * @param requestedIndex the page the caller asked for, clamped into range
     */
    public static CataloguePage of(int entryCount, int pageSize, int requestedIndex) {
        if (entryCount < 0) {
            throw new IllegalArgumentException("entryCount must not be negative: " + entryCount);
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive: " + pageSize);
        }

        int pages = entryCount == 0 ? 1 : (entryCount + pageSize - 1) / pageSize;
        int index = Math.clamp(requestedIndex, 0, pages - 1);
        int from = index * pageSize;
        int to = Math.min(from + pageSize, entryCount);
        return new CataloguePage(index, pages, from, to);
    }

    /** How many entries appear on this page. */
    public int size() {
        return toExclusive - fromInclusive;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean hasPrevious() {
        return index > 0;
    }

    public boolean hasNext() {
        return index + 1 < pageCount;
    }

    /** One-based page number, for display. */
    public int displayIndex() {
        return index + 1;
    }

    /** The same page moved by {@code delta}, clamped to the listing - used by the next/previous buttons. */
    public CataloguePage shift(int entryCount, int pageSize, int delta) {
        return of(entryCount, pageSize, index + delta);
    }
}
