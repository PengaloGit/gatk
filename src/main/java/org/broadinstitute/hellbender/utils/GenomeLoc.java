package org.broadinstitute.hellbender.utils;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.util.Locatable;
import org.broadinstitute.hellbender.exceptions.GATKException;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Genome location representation.  It is *** 1 *** based closed.  Note that GenomeLocs start and stop values
 * can be any positive or negative number, by design.  Bound validation is a feature of the GenomeLocParser,
 * and not a fundamental constraint of the GenomeLoc
 *
 * This class is not intended to be extended outside of the core GATK engine and tests.
 */
public class GenomeLoc implements Comparable<GenomeLoc>, Serializable, HasGenomeLocation, Locatable {
    private static final long serialVersionUID = 1L;

    /**
     * the basic components of a genome loc, its contig index,
     * start and stop position, and (optionally) the contig name
     */
    protected final int contigIndex;
    protected final int start;
    protected final int stop;
    protected final String contigName;

    /**
     * A static constant to use when referring to the unmapped section of a datafile
     * file.  The unmapped region cannot be subdivided.  Only this instance of
     * the object may be used to refer to the region, as '==' comparisons are used
     * in comparators, etc.
     */
    // TODO - WARNING WARNING WARNING code somehow depends on the name of the contig being null!
    public static final GenomeLoc UNMAPPED = new GenomeLoc((String)null);
    public static final GenomeLoc WHOLE_GENOME = new GenomeLoc("all");

    // --------------------------------------------------------------------------------------------------------------
    //
    // constructors
    //
    // --------------------------------------------------------------------------------------------------------------

    protected GenomeLoc( final String contig, final int contigIndex, final int start, final int stop ) {
        this.contigName = contig;
        this.contigIndex = contigIndex;
        this.start = start;
        this.stop = stop;
    }

    /** Unsafe constructor for special constant genome locs */
    private GenomeLoc( final String contig ) {
        this.contigName = contig;
        this.contigIndex = -1;
        this.start = 0;
        this.stop = 0;
    }

    //
    // Accessors
    //
    @Override
    public final GenomeLoc getLocation() { return this; }

    public final GenomeLoc getStartLocation() { return new GenomeLoc(getContig(),getContigIndex(),getStart(),getStart()); }

    public final GenomeLoc getStopLocation() { return new GenomeLoc(getContig(),getContigIndex(),getStop(),getStop()); }

    /**
     * @return the name of the contig of this GenomeLoc
     */
    @Override
    public final String getContig() {
        return this.contigName;
    }

    public final int getContigIndex() { return this.contigIndex; }
    @Override
    public final int getStart()    { return this.start; }

    @Override
    public int getEnd() {
        return getStop();
    }

    public final int getStop()     { return this.stop; }

    public final String toString()  {
        if(isUnmapped()) {
            return "unmapped";
        } else if ( throughEndOfContigP() && atBeginningOfContigP() ) {
            return getContig();
        } else if ( throughEndOfContigP() || getStart() == getStop() ) {
            return String.format("%s:%d", getContig(), getStart());
        } else {
            return String.format("%s:%d-%d", getContig(), getStart(), getStop());
        }
    }

    private boolean throughEndOfContigP() { return stop == Integer.MAX_VALUE; }

    private boolean atBeginningOfContigP() { return start == 1; }

    public final boolean disjointP(final GenomeLoc that) {
        return this.contigIndex != that.contigIndex || this.start > that.stop || that.start > this.stop;
    }

    public final boolean discontinuousP(final GenomeLoc that) {
        return this.contigIndex != that.contigIndex || (this.start - 1) > that.stop || (that.start - 1) > this.stop;
    }

    public final boolean overlapsP(final GenomeLoc that) {
        return ! disjointP(that);
    }

    public final boolean contiguousP(final GenomeLoc that) {
        return ! discontinuousP(that);
    }

    public final boolean isUnmapped() { return this == UNMAPPED; }


    /**
     * Returns a new GenomeLoc that represents the entire span of this and that.  Requires that
     * this and that GenomeLoc are contiguous and both mapped
     */
    public GenomeLoc merge( final GenomeLoc that ) throws GATKException {
        Utils.validateArg(this.isUnmapped() == that.isUnmapped(), "Tried to merge a mapped and an unmapped genome loc");
        if (this.isUnmapped()) {
            return UNMAPPED;
        }
        Utils.validateArg(this.contiguousP(that), "The two genome loc's need to be contiguous");

        return new GenomeLoc(getContig(), this.contigIndex,
                Math.min( getStart(), that.getStart() ),
                Math.max( getStop(), that.getStop()) );
    }

    /**
     * Splits the contig into to regions: [start,split point) and [split point, end].
     * @param splitPoint The point at which to split the contig.  Must be contained in the given interval.
     * @return A two element array consisting of the genome loc before the split and the one after.
     */
    public GenomeLoc[] split(final int splitPoint) {
        if(splitPoint < getStart() || splitPoint > getStop()) {
            throw new GATKException(String.format("Unable to split contig %s at split point %d; split point is not contained in region.", this, splitPoint));
        }
        return new GenomeLoc[] { new GenomeLoc(getContig(),contigIndex,getStart(),splitPoint-1), new GenomeLoc(getContig(),contigIndex,splitPoint,getStop()) };
    }

    public GenomeLoc union( final GenomeLoc that ) { return merge(that); }

    public GenomeLoc intersect( final GenomeLoc that ) throws GATKException {
        Utils.validateArg(this.isUnmapped() == that.isUnmapped(), "Tried to merge a mapped and an unmapped genome loc");
        if (this.isUnmapped()) {
            return UNMAPPED;
        }

        Utils.validateArg(this.overlapsP(that), "The two genome loc's need to overlap");

        return new GenomeLoc(getContig(), this.contigIndex,
                Math.max(getStart(), that.getStart()),
                Math.min( getStop(), that.getStop()) );
    }

    public final List<GenomeLoc> subtract( final GenomeLoc that ) {
        Utils.validateArg(this.isUnmapped() == that.isUnmapped(), "Tried to merge a mapped and an unmapped genome loc");
        if (this.isUnmapped()) {
            return Arrays.asList(UNMAPPED);
        } else if (this.equals(that)) {
            return Collections.emptyList();
        }
        Utils.validateArg(this.overlapsP(that), "The two genome loc's need to overlap");

        /**
         * |----------------- old region (g) -------------|
         *        |----- to delete (e) ------|
         *
         * yields
         * |------|             and           |--------|
         *
         * If e starts at or before g the left interval has non-positive size.  Similarly, if e ends at or after g the right
         * interval has non-positive size.  So we produce two candidate locs and then filter by size to get 0, 1, or 2 final locs.
         */
        final GenomeLoc leftLoc = new GenomeLoc(this.getContig(), getContigIndex(), this.getStart(), that.getStart() - 1);
        final GenomeLoc rightLoc = new GenomeLoc(getContig(), getContigIndex(), that.getStop() + 1, this.getStop());
        return Arrays.asList(leftLoc, rightLoc).stream().filter(loc -> loc.size() > 0).collect(Collectors.toList());
    }

    public final boolean containsP(final GenomeLoc that) {
        return onSameContig(that) && getStart() <= that.getStart() && getStop() >= that.getStop();
    }

    public final boolean onSameContig(final GenomeLoc that) {
        return (this.contigIndex == that.contigIndex);
    }

    public final int distance( final GenomeLoc that ) {
        return this.onSameContig(that) ? Math.abs(this.getStart() - that.getStart()) : Integer.MAX_VALUE;
    }

    public final boolean isBetween( final GenomeLoc left, final GenomeLoc right ) {
        return this.compareTo(left) > -1 && this.compareTo(right) < 1;
    }

    /**
     * Tests whether this contig is completely before contig 'that'.
     * @param that Contig to test against.
     * @return true if this contig ends before 'that' starts; false if this is completely after or overlaps 'that'.
     */
    public final boolean isBefore( final GenomeLoc that ) {
        final int comparison = this.compareContigs(that);
        return ( comparison == -1 || ( comparison == 0 && this.getStop() < that.getStart() ));
    }

    /**
     * Tests whether this contig is completely after contig 'that'.
     * @param that Contig to test against.
     * @return true if this contig starts after 'that' ends; false if this is completely before or overlaps 'that'.
     */
    public final boolean isPast( final GenomeLoc that ) {
        final int comparison = this.compareContigs(that);
        return ( comparison == 1 || ( comparison == 0 && this.getStart() > that.getStop() ));
    }

    /**
     * Return the minimum distance between any pair of bases in this and that GenomeLocs:
     */
    public final int minDistance( final GenomeLoc that ) {
        if (!this.onSameContig(that)) {
            return Integer.MAX_VALUE;
        } else if (this.isBefore(that)) {
            return distanceFirstStopToSecondStart(this, that);
        } else if (that.isBefore(this)) {
            return distanceFirstStopToSecondStart(that, this);
        } else { // this and that overlap [and possibly one contains the other]:
            return 0;
        }
    }

    private static int distanceFirstStopToSecondStart(final GenomeLoc locFirst, final GenomeLoc locSecond) {
        return locSecond.getStart() - locFirst.getStop();
    }

    /**
     * Check to see whether two genomeLocs are equal.
     * Note that this implementation ignores the contigInfo object.
     * @param other Other contig to compare.
     */
    @Override
    public boolean equals(final Object other) {
        if(other == null) {
            return false;
        }
        if(other instanceof GenomeLoc) {
            final GenomeLoc otherGenomeLoc = (GenomeLoc)other;
            return this.contigIndex == otherGenomeLoc.contigIndex &&
                    this.start == otherGenomeLoc.start &&
                    this.stop == otherGenomeLoc.stop;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return start << 16 | stop << 4 | contigIndex;
    }


    /**
     * conpare this genomeLoc's contig to another genome loc
     * @param that the genome loc to compare contigs with
     * @return 0 if equal, -1 if that.contig is greater, 1 if this.contig is greater
     */
    public final int compareContigs( final GenomeLoc that ) {
        return Integer.compare(this.contigIndex, that.contigIndex);
    }

    @Override
    public int compareTo( final GenomeLoc that ) {
        if ( this == that ) {
             return 0;
        } else if(this.isUnmapped()) {
            return 1;
        } else if(that.isUnmapped()) {
            return -1;
        } else {
            final int cmpContig = compareContigs(that);
            if ( cmpContig != 0 ) {
                return cmpContig;
            } else {
                final int cmpStart = Integer.compare(this.getStart(), that.getStart());
                return cmpStart != 0 ? cmpStart : Integer.compare(this.getStop(), that.getStop());
            }
        }
    }

    /**
     * How many BPs are covered by this locus?
     * @return Number of BPs covered by this locus.  According to the semantics of GenomeLoc, this should
     *         never be < 1.
     */
    public int size() {
        return stop - start + 1;
    }

    /**
     * reciprocialOverlap: what is the min. percent of gl1 and gl2 covered by both
     *
     * gl1.s ---------- gk1.e
     * gl2.s ---------- gl2.e
     * 100%
     *
     * gl1.s ---------- gk1.e
     *      gl2.s ---------- gl2.e
     * 50%
     *
     * gl1.s ---------- gk1.e
     *      gl2.s -------------------- gl2.e
     * 25% (50% for gl1 but only 25% for gl2)
     */
    public final double reciprocialOverlapFraction(final GenomeLoc o) {
        return overlapsP(o) ? Math.min(overlapPercent(this, o), overlapPercent(o, this)) : 0.0;
    }

    private static double overlapPercent(final GenomeLoc gl1, final GenomeLoc gl2) {
        return (1.0 * gl1.intersect(gl2).size()) / gl1.size();
    }

    /**
     * Returns the maximum GenomeLoc of this and other
     * @param other another non-null genome loc
     * @return the max of this and other
     */
    public GenomeLoc max(final GenomeLoc other) {
        final int cmp = this.compareTo(other);
        return cmp == -1 ? other : this;
    }

    /**
     * Merges 2 *contiguous* locs into 1
     *
     * @param a   GenomeLoc #1
     * @param b   GenomeLoc #2
     * @return one merged loc
     */
    public static <T extends GenomeLoc> GenomeLoc merge(final T a, final T b) {
        Utils.validateArg(!a.isUnmapped() && !b.isUnmapped(), "Tried to merge unmapped genome locs");
        Utils.validateArg(a.contiguousP(b), "The two genome locs need to be contiguous");
        return new GenomeLoc(a.getContig(), a.contigIndex, Math.min(a.getStart(), b.getStart()), Math.max(a.getStop(), b.getStop()));
    }

    /**
     * Merges a list of *sorted* *contiguous* locs into 1
     *
     * @param sortedLocs a sorted list of contiguous locs
     * @return one merged loc
     */
    public static <T extends GenomeLoc> GenomeLoc merge(final SortedSet<T> sortedLocs) {
        Utils.validateArg(sortedLocs.stream().noneMatch(GenomeLoc::isUnmapped), "Tried to merge unmapped genome locs");
        GenomeLoc result = null;
        for ( final GenomeLoc loc : sortedLocs ) {
            result = result == null ? loc : merge(result, loc);
        }

        return result;
    }

    /**
     * Calculates the distance between two genomeLocs across contigs (if necessary).
     *
     * Returns minDistance(other) if in same contig.
     * Works with intervals!
     * Uses the SAMFileHeader to extract the size of the contigs and follows the order in the dictionary.
     *
     * @param other         the genome loc to compare to
     * @param samFileHeader the contig information
     * @return the sum of all the bases in between the genomeLocs, including entire contigs
     */
    public long distanceAcrossContigs(final GenomeLoc other, final SAMFileHeader samFileHeader) {
        if (onSameContig(other)) {
            return minDistance(other);
        }

        // add the distance from the first genomeLoc to the end of it's contig and the distance from the
        // second genomeLoc to the beginning of it's contig.
        long distance = 0;
        if (contigIndex < other.contigIndex) {
            distance += samFileHeader.getSequence(contigIndex).getSequenceLength() - stop;
            distance += other.start;
        } else {
            distance += samFileHeader.getSequence(other.contigIndex).getSequenceLength() - other.stop;
            distance += start;
        }

        // add any contig (in its entirety) in between the two genomeLocs
        for (int i=Math.min(this.contigIndex, other.contigIndex) + 1; i < Math.max(this.contigIndex, other.contigIndex); i++) {
            distance += samFileHeader.getSequence(i).getSequenceLength();
        }
        return distance;
    }

    /**
     * create a new genome loc from an existing loc, with a new start position
     * Note that this function will NOT explicitly check the ending offset, in case someone wants to
     * set the start of a new GenomeLoc pertaining to a read that goes off the end of the contig.
     *
     * @param loc   the old location
     * @param start a new start position
     *
     * @return a newly allocated GenomeLoc as loc but with start == start
     */
    public static GenomeLoc setStart(final GenomeLoc loc, final int start) {
        Utils.nonNull(loc);
        return new GenomeLoc(loc.getContig(), loc.getContigIndex(), start, loc.getStop());
    }

    /**
     * create a new genome loc from an existing loc, with a new stop position
     * Note that this function will NOT explicitly check the ending offset, in case someone wants to
     * set the stop of a new GenomeLoc pertaining to a read that goes off the end of the contig.
     *
     * @param loc  the old location
     * @param stop a new stop position
     *
     * @return a newly allocated GenomeLoc as loc but with stop == stop
     */
    public static GenomeLoc setStop(final GenomeLoc loc, final int stop) {
        Utils.nonNull(loc);
        return new GenomeLoc(loc.getContig(), loc.getContigIndex(), loc.start, stop);
    }

    /**
     * Returns a new GenomeLoc that represents the region between the endpoints of this and that. Requires that
     * this and that GenomeLoc are both mapped.
     */
    public GenomeLoc endpointSpan(final GenomeLoc that) {
        Utils.validateArg(!this.isUnmapped() && !that.isUnmapped(), "Cannot get endpoint span for unmerged genome locs");
        Utils.validateArg(this.getContig().equals(that.getContig()), "Cannot get endpoint span for genome locs on different contigs");

        return new GenomeLoc(getContig(),this.contigIndex,Math.min(getStart(),that.getStart()),Math.max(getStop(),that.getStop()));
    }
}
