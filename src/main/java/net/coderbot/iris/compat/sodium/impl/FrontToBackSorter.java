package net.coderbot.iris.compat.sodium.impl;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Stable, allocation-amortized region ordering. Never removes or modifies a draw batch. */
public final class FrontToBackSorter<T> {
    private Entry[] scratch=new Entry[0];
    private static final Comparator<Entry> ORDER=Comparator.comparingDouble(e -> e.distance);
    public boolean sort(List<T> list,ToDoubleFunction<T> distance) {
        int count=list.size();
        if(count<2) return false;
        if(scratch.length<count) {
            scratch=new Entry[count];
            for(int i=0;i<count;i++) scratch[i]=new Entry();
        }
        try {
            boolean ordered=true;
            double previous=Double.NEGATIVE_INFINITY;
            for(int i=0;i<count;i++) {
                T value=list.get(i);
                double d=distance.applyAsDouble(value);
                if(!Double.isFinite(d)) return false;
                scratch[i].value=value; scratch[i].distance=d;
                if(d<previous) ordered=false;
                previous=d;
            }
            if(ordered) return false;
            Arrays.sort(scratch,0,count,ORDER);
            for(int i=0;i<count;i++) {
                @SuppressWarnings("unchecked") T value=(T)scratch[i].value;
                list.set(i,value);
            }
            return true;
        } finally {
            for(int i=0;i<count;i++) scratch[i].value=null;
            // Avoid retaining scratch capacity after extreme render-distance excursions.
            if(scratch.length>4096) scratch=new Entry[0];
        }
    }
    private static final class Entry { Object value; double distance; }
}
