package dev.kage.common;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Carries an IBinder inside a Bundle.
 *
 * Bundle#putBinder is not usable here: the framework strips plain binder values that travel
 * between processes in some releases, while a Parcelable that writes its own strong binder in
 * writeToParcel survives everywhere. Same trick the Shizuku project uses.
 */
public class BinderContainer implements Parcelable {

    public final IBinder binder;

    public BinderContainer(IBinder binder) {
        this.binder = binder;
    }

    protected BinderContainer(Parcel in) {
        this.binder = in.readStrongBinder();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(binder);
    }

    public static final Creator<BinderContainer> CREATOR = new Creator<BinderContainer>() {
        @Override
        public BinderContainer createFromParcel(Parcel source) {
            return new BinderContainer(source);
        }

        @Override
        public BinderContainer[] newArray(int size) {
            return new BinderContainer[size];
        }
    };
}
