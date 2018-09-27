package app.intra.util;

import android.os.Parcel;

import org.junit.Test;

import static org.junit.Assert.*;

public class AppInfoTest {
  final String LABEL1 = "label1", LABEL2 = "label2";
  final String NAME1 = "name.1", NAME2 = "name.2";
  final AppInfo APP1 = new AppInfo(LABEL1, NAME1);
  final AppInfo APP2 = new AppInfo(LABEL2, NAME2);
  final AppInfo NULL1 = new AppInfo(null, NAME1);
  final AppInfo NULL2 = new AppInfo(null, NAME2);

  @Test
  public void compareToEqual() {
    assertEquals(0, APP1.compareTo(new AppInfo(LABEL1, NAME1)));
    assertEquals(0, NULL1.compareTo(new AppInfo(null, NAME1)));
    assertEquals(0, APP1.compareTo(APP1));
    assertEquals(0, NULL1.compareTo(NULL1));
  }

  @Test
  public void compareToUnequal() {
    assertEquals(-1, APP1.compareTo(APP2));
    assertEquals(1, APP2.compareTo(APP1));
    assertEquals(-1, APP1.compareTo(NULL1));
    assertEquals(1, NULL1.compareTo(APP1));
    assertEquals(-1, APP2.compareTo(NULL2));
    assertEquals(1, NULL2.compareTo(APP2));
    assertEquals(-1, APP2.compareTo(NULL1));
    assertEquals(1, NULL1.compareTo(APP2));
    assertEquals(-1, NULL1.compareTo(NULL2));
    assertEquals(1, NULL2.compareTo(NULL1));
  }

  private void verifyParcelSerializationRoundtrip(AppInfo info) {
    Parcel parcel1 = Parcel.obtain();
    info.writeToParcel(parcel1, 0);
    byte[] data = parcel1.marshall();
    parcel1.recycle();
    Parcel parcel2 = Parcel.obtain();
    parcel2.unmarshall(data, 0, data.length);
    parcel2.setDataPosition(0);
    AppInfo copy = AppInfo.CREATOR.createFromParcel(parcel2);
    parcel2.recycle();
    assertEquals(0, info.compareTo(copy));
  }

  @Test
  public void writeToParcel() {
    verifyParcelSerializationRoundtrip(APP1);
    verifyParcelSerializationRoundtrip(NULL1);
  }
}