package com.telenor.possumlib.interfaces;

import com.google.android.gms.vision.face.Face;

public interface IFaceFound {
    void faceFound(Face face, byte[] byteArray);
    void imageTaken(byte[] byteArray);
}