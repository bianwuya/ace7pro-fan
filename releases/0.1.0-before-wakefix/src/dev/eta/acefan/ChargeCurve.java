package dev.eta.acefan;

/** Pure policy: experimental battery-temperature demand; zero means no extra demand. */
public final class ChargeCurve {
    private static final double[] UP={33,35,37,39,41};
    private static final int[] LEVEL={6,10,14,18,20};
    private int index=-1;
    private long coolingAt=-1;
    public synchronized int update(double temperature,long elapsed,boolean allowed){
        if(!allowed||Double.isNaN(temperature)||Double.isInfinite(temperature)||temperature<0||temperature>65){reset();return 0;}
        int desired=-1;for(int i=0;i<UP.length;i++)if(temperature>=UP[i])desired=i;
        if(desired>index){index=desired;coolingAt=-1;}
        else if(index>=0&&temperature<UP[index]-1){
            if(coolingAt<0)coolingAt=elapsed;
            if(elapsed-coolingAt>=30000){index=desired;coolingAt=-1;}
        }else coolingAt=-1;
        return level();
    }
    public synchronized void reset(){index=-1;coolingAt=-1;}
    public synchronized int level(){return index<0?0:LEVEL[index];}
}
