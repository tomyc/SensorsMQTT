package raunio.gforcetracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * Created by Tomasz on 30.03.2016.
 */
public class BallPanel extends SurfaceView implements SurfaceHolder.Callback {

    private float panelHeight;
    private float panelWidth;
    private static final float SCALE = 10f;

    public BallPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
        setFocusable(true);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        MapsActivity.ballPanel = this;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        this.panelHeight=(float)height;
        this.panelWidth=(float)width;
        Canvas c =holder.lockCanvas();
        drawBall(c,false,0.0f,0.0f,0.0f);
        holder.unlockCanvasAndPost(c);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        MapsActivity.ballPanel = this;
    }

    public void drawBall(Canvas c, boolean ball, float x, float y, float z){
        c.drawRGB(0, 0, 0);
        Paint p = new Paint();
        p.setColor(Color.WHITE);
        float halfWidth = panelWidth/2.0f;
        float halfHeight = panelHeight/2.0f;
        c.drawLine(halfWidth,0,halfWidth,panelHeight,p);
        c.drawLine(0,halfHeight,panelWidth,halfHeight,p);
        if (ball){
            float cx = halfWidth + (halfWidth*(x/SCALE));
            float cy = halfHeight + (halfHeight*(y/SCALE));
            float radius = 50+(50*(z/SCALE));
            if (radius<0.0f){
                radius = 1.0f;
            }
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx,cy,radius,p);
        }
    }
}
