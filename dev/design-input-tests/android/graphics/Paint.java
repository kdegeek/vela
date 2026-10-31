package android.graphics;
/** Test-only non-rendering initialization carrier. No pixels or UI are exercised. */
public class Paint {
 public enum Style { FILL, STROKE, FILL_AND_STROKE }
 public Paint() {} public Paint(int flags) {}
 public void setColor(int color) {} public void setStrokeWidth(float width) {} public void setStyle(Style style) {}
}
