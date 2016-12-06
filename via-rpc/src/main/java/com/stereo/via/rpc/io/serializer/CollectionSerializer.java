package com.stereo.via.rpc.io.serializer;

import com.stereo.via.rpc.io.AbstractOutput;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

/**
 * Serializing a JDK 1.2 Collection.
 */
public class CollectionSerializer extends AbstractSerializer {
	private boolean _sendJavaType = true;

	/**
	 * Set true if the java type of the collection should be sent.
	 */
	public void setSendJavaType(boolean sendJavaType) {
		_sendJavaType = sendJavaType;
	}

	/**
	 * Return true if the java type of the collection should be sent.
	 */
	public boolean getSendJavaType() {
		return _sendJavaType;
	}

	public void writeObject(Object obj, AbstractOutput out)
			throws IOException {
		if (out.addRef(obj))
			return;

		Collection list = (Collection) obj;

		Class cl = obj.getClass();
		boolean hasEnd;

		if (cl.equals(ArrayList.class)
				|| !Serializable.class.isAssignableFrom(cl)) {
			hasEnd = out.writeListBegin(list.size(), null);
		} else if (!_sendJavaType) {
			hasEnd = false;

			// hessian/3a19
			for (; cl != null; cl = cl.getSuperclass()) {
				if (cl.getName().startsWith("java.")) {
					hasEnd = out.writeListBegin(list.size(), cl.getName());
					break;
				}
			}

			if (cl == null)
				hasEnd = out.writeListBegin(list.size(), null);
		} else {
			hasEnd = out.writeListBegin(list.size(), obj.getClass().getName());
		}

		Iterator iter = list.iterator();
		while (iter.hasNext()) {
			Object value = iter.next();

			out.writeObject(value);
		}

		if (hasEnd)
			out.writeListEnd();
	}
}
