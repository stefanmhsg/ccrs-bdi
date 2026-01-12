package ccrs.jacamo.jason.hypermedia.hypermedea;

import org.hypermedea.op.Operation;
import org.hypermedea.op.Response;

public interface InteractionLogSink {

    void onRequest(Operation op, long timestamp);

    void onResponse(Operation op, Response response, long timestamp);

    void onError(Operation op, long timestamp);
}
