/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2010  Mickael Guessant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package davmail.exchange.ews;

import java.io.IOException;
import java.io.Writer;

/**
 * Item id.
 */
public class ItemId {
    protected final String id;
    protected final String changeKey;

    /**
     * Create Item id.
     *
     * @param id        item id
     * @param changeKey item change key
     */
    public ItemId(String id, String changeKey) {
        this.id = id;
        this.changeKey = changeKey;
    }

    /**
     * Build Item id from response item.
     *
     * @param item response item
     */
    public ItemId(EWSMethod.Item item) {
        this.id = item.get("ItemId");
        this.changeKey = item.get("ChangeKey");
    }

    /**
     * Write item id as XML.
     *
     * @param writer request writer
     * @throws IOException on error
     */
    public void write(Writer writer) throws IOException {
        writer.write("<t:ItemId Id=\"");
        writer.write(id);
        if (changeKey != null) {
            writer.write("\" ChangeKey=\"");
            writer.write(changeKey);
        }
        writer.write("\"/>");
    }
}