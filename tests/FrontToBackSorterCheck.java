package net.coderbot.iris.compat.sodium.impl;

import java.util.*;

public final class FrontToBackSorterCheck {
    private static final class Item { final double d; Item(double d) { this.d=d; } }
    public static void main(String[] args) {
        FrontToBackSorter<Item> sorter=new FrontToBackSorter<>();
        Random random=new Random(948);
        for(int n:new int[]{0,1,2,16,128,4096,4097,32}) for(int trial=0;trial<20;trial++) {
            ArrayList<Item> list=new ArrayList<>();
            for(int i=0;i<n;i++) list.add(new Item(random.nextInt(200)));
            ArrayList<Item> expected=new ArrayList<>(list);
            expected.sort(Comparator.comparingDouble(i->i.d));
            sorter.sort(list,i->i.d);
            for(int i=0;i<n;i++) check(list.get(i)==expected.get(i));
            check(!sorter.sort(list,i->i.d));
        }
        ArrayList<Item> invalid=new ArrayList<>(Arrays.asList(new Item(4),new Item(Double.NaN),new Item(1)));
        Item first=invalid.get(0);
        check(!sorter.sort(invalid,i->i.d) && invalid.get(0)==first);
        // Reordering opaque/cutout samples must preserve nearest-depth results; no fragments are removed.
        for(int trial=0;trial<1000;trial++) {
            ArrayList<Item> fragments=new ArrayList<>();
            for(int i=0;i<100;i++) fragments.add(new Item(random.nextDouble()*100));
            double before=depth(fragments);
            sorter.sort(fragments,i->i.d);
            check(depth(fragments)==before);
        }
        System.out.println("Front-to-back checks passed: stable ties, exact permutation, invalid input, capacity reuse and opaque depth result");
    }
    private static double depth(List<Item> fragments) {
        double z=Double.POSITIVE_INFINITY;
        for(Item f:fragments) if(((int)f.d%3)!=0 && f.d<z) z=f.d;
        return z;
    }
    private static void check(boolean ok) { if(!ok) throw new AssertionError(); }
}
