package sk.technicallyhere;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.time.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real native PDF smoke test. Run connectedDebugAndroidTest with a device/emulator attached. */
@RunWith(AndroidJUnit4.class)
public class NativePdfDeviceTest {
  @Test
  public void nativePdfOpensInAndroidRenderer() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    JSONObject addon =
        J.obj(
            "template",
            Core.standardTemplate(),
            "values",
            J.obj("project", "Native test"),
            "weeks",
            new JSONObject(),
            "days",
            new JSONObject());
    File file = new File(context.getCacheDir(), "native-pdf-test.pdf");
    try (OutputStream out = new FileOutputStream(file)) {
      AddonPdf.write(addon, new ArrayList<>(), Core.monday(LocalDate.now()), out);
    }
    assertTrue(file.length() > 100);
    try (ParcelFileDescriptor descriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer = new PdfRenderer(descriptor)) {
      assertTrue(renderer.getPageCount() > 0);
      try (PdfRenderer.Page page = renderer.openPage(0)) {
        assertTrue(page.getWidth() > 0);
        assertTrue(page.getHeight() > 0);
      }
    }
    assertTrue(file.delete());
  }
}
