package kbaserelationengine.events;

import java.io.IOException;


public interface ObjectStatusEventListener {
	public void statusChanged(ObjectStatusEvent event) throws IOException;
}