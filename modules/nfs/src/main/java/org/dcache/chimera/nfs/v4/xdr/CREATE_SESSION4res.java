/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v4.xdr;
import org.dcache.chimera.nfs.nfsstat;
import org.dcache.chimera.nfs.v4.*;
import org.dcache.xdr.*;
import java.io.IOException;

public class CREATE_SESSION4res implements XdrAble {
    public int csr_status;
    public CREATE_SESSION4resok csr_resok4;

    public CREATE_SESSION4res() {
    }

    public CREATE_SESSION4res(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        xdr.xdrEncodeInt(csr_status);
        switch ( csr_status ) {
        case nfsstat.NFS_OK:
            csr_resok4.xdrEncode(xdr);
            break;
        default:
            break;
        }
    }

    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        csr_status = xdr.xdrDecodeInt();
        switch ( csr_status ) {
        case nfsstat.NFS_OK:
            csr_resok4 = new CREATE_SESSION4resok(xdr);
            break;
        default:
            break;
        }
    }

}
// End of CREATE_SESSION4res.java
