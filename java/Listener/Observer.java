package Listener;

import java.util.ArrayList;

/**
 * Abstraction for Observer pattern
 */
public abstract class Observer {
    protected ArrayList<IObserverListener> listeners;

    /**
     * Constructor
     */
    public Observer(){
        this.listeners = new ArrayList<>();
    }

    /**
     * Fire Signal to all Observer listeners
     * @param signal
     */
    protected void fireSignal(String signal){
        for(IObserverListener l : this.listeners){
            l.onSignaled(signal);
        }
    }

    /**
     * Subscribe to Observer
     * @param listener
     */
    public void addListener(IObserverListener listener){
        this.listeners.add(listener);
    }

    /**
     * Unsubscribe from Observer
     * @param listener
     */
    public void removeListener(IObserverListener listener){
        this.listeners.remove(listener);
    }
}
