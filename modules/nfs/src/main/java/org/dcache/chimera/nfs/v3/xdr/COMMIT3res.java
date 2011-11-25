/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v3.xdr;
import org.dcache.xdr.*;
import java.io.IOException;
import org.dcache.chimera.nfs.nfsstat;

public class COMMIT3res implements XdrAble {
    public int status;
    public COMMIT3resok resok;
    public COMMIT3resfail resfail;

    public COMMIT3res() {
    }

    public COMMIT3res(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        xdr.xdrEncodeInt(status);
        switch ( status ) {
        case nfsstat.NFS_OK:
            resok.xdrEncode(xdr);
            break;
        default:
            resfail.xdrEncode(xdr);
            break;
        }
    }

    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        status = xdr.xdrDecodeInt();
        switch ( status ) {
        case nfsstat.NFS_OK:
            resok = new COMMIT3resok(xdr);
            break;
        default:
            resfail = new COMMIT3resfail(xdr);
            break;
        }
    }

}
// End of COMMIT3res.java
