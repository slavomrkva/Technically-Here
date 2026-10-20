package sk.technicallyhere;

import android.content.Context;
import android.graphics.*;
import android.util.Base64;
import android.view.*;
import java.io.ByteArrayOutputStream;

public final class SignatureView extends View {
  private final Bitmap bitmap = Bitmap.createBitmap(900, 260, Bitmap.Config.ARGB_8888);
  private final Canvas backing = new Canvas(bitmap);
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private final Rect destination = new Rect();
  private boolean drawing = false, hasInk = false;

  public SignatureView(Context context) {
    this(context, "");
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldW, int oldH) {
    destination.set(0, 0, w, h);
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  public SignatureView(Context context, String initial) {
    super(context);
    backing.drawColor(Color.WHITE);
    paint.setColor(Color.rgb(28, 25, 20));
    paint.setStrokeWidth(3);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    if (initial.startsWith("data:image/png;base64,"))
      try {
        byte[] b = Base64.decode(initial.substring(initial.indexOf(',') + 1), Base64.DEFAULT);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, bounds);
        Bitmap loaded =
            bounds.outWidth > 0
                    && bounds.outHeight > 0
                    && bounds.outWidth * 1L * bounds.outHeight <= 4000000
                ? BitmapFactory.decodeByteArray(b, 0, b.length)
                : null;
        if (loaded != null) {
          backing.drawBitmap(loaded, null, new Rect(0, 0, 900, 260), paint);
          loaded.recycle();
          hasInk = true;
        }
      } catch (Exception ignored) {
      }
    setContentDescription("Draw your signature");
    setMinimumHeight(130);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.drawColor(Color.WHITE);
    canvas.drawBitmap(bitmap, null, destination, null);
    if (drawing) {
      canvas.save();
      canvas.scale(getWidth() / 900f, getHeight() / 260f);
      canvas.drawPath(path, paint);
      canvas.restore();
    }
  }

  @Override
  public boolean onTouchEvent(android.view.MotionEvent event) {
    float x = event.getX() * 900 / Math.max(1, getWidth()),
        y = event.getY() * 260 / Math.max(1, getHeight());
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        drawing = true;
        path.reset();
        path.moveTo(x, y);
        return true;
      case MotionEvent.ACTION_MOVE:
        if (drawing) {
          path.lineTo(x, y);
          invalidate();
        }
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (drawing) {
          path.lineTo(x, y);
          backing.drawPath(path, paint);
          drawing = false;
          hasInk = true;
          invalidate();
        }
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return true;
      default:
        return true;
    }
  }

  public void clear() {
    backing.drawColor(Color.WHITE);
    path.reset();
    drawing = false;
    hasInk = false;
    invalidate();
  }

  public String value() {
    if (!hasInk) return "";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
  }
}
