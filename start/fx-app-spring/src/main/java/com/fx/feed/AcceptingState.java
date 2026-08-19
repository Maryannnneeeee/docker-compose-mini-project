package com.fx.feed;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Whether the internal rate feed is currently allowed to write new ticks. In-memory only —
 * a restart resets it to the default, same as any other in-process toggle. */
@Component
public class AcceptingState {

    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public boolean isOn() { return accepting.get(); }

    public boolean set(boolean value) {
        accepting.set(value);
        return value;
    }
}
