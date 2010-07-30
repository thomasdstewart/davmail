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
package davmail.exchange;

import davmail.Settings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

/**
 * VCalendar object.
 */
public class VCalendar extends VObject {
    protected VObject firstVevent;
    protected VObject vTimezone;
    protected String email;

    /**
     * Create VCalendar object from reader;
     *
     * @param reader stream reader
     * @param email  current user email
     * @throws IOException on error
     */
    public VCalendar(BufferedReader reader, String email) throws IOException {
        super(reader);
        if (!"VCALENDAR".equals(type)) {
            throw new IOException("Invalid type: " + type);
        }
        this.email = email;
    }

    /**
     * Create VCalendar object from reader;
     *
     * @param vCalendarBody item body
     * @param email         current user email
     * @throws IOException on error
     */
    public VCalendar(String vCalendarBody, String email) throws IOException {
        this(new ICSBufferedReader(new StringReader(vCalendarBody)), email);
    }

    @Override
    protected void addVObject(VObject vObject) {
        super.addVObject(vObject);
        if (firstVevent == null && "VEVENT".equals(vObject.type)) {
            firstVevent = vObject;
        }
        if ("VTIMEZONE".equals(vObject.type)) {
            vTimezone = vObject;
        }
    }

    protected boolean isAllDay(VObject vObject) {
        VProperty dtstart = vObject.getProperty("DTSTART");
        return dtstart != null && dtstart.hasParam("VALUE", "DATE");
    }

    protected boolean hasCdoAllDay(VObject vObject) {
        return vObject.getProperty("X-MICROSOFT-CDO-ALLDAYEVENT") != null;
    }

    protected boolean hasCdoBusyStatus(VObject vObject) {
        return vObject.getProperty("X-MICROSOFT-CDO-BUSYSTATUS") != null;
    }

    protected boolean isCdoAllDay(VObject vObject) {
        return "TRUE".equals(vObject.getPropertyValue("X-MICROSOFT-CDO-ALLDAYEVENT"));
    }

    protected boolean isAppleiCal() {
        return getPropertyValue("PRODID").contains("iCal");
    }

    protected String getOrganizer() {
        String organizer = firstVevent.getPropertyValue("ORGANIZER");
        if (organizer.startsWith("MAILTO:")) {
            return organizer.substring(7);
        } else {
            return organizer;
        }
    }

    protected String getMethod() {
        return getPropertyValue("METHOD");
    }

    protected void fixVCalendar(boolean fromServer) {
        // append missing method
        if (getProperty("METHOD") == null) {
            setPropertyValue("METHOD", "PUBLISH");
        }
        // iCal 4 global private flag
        if (fromServer) {
            setPropertyValue("X-CALENDARSERVER-ACCESS", getCalendarServerAccess());
        }

        String calendarServerAccess = getPropertyValue("X-CALENDARSERVER-ACCESS");

        // TODO: patch timezone for iPhone
        // iterate over vObjects
        for (VObject vObject : vObjects) {
            if ("VEVENT".equals(vObject.type)) {
                if (calendarServerAccess != null) {
                    vObject.setPropertyValue("CLASS", getEventClass(calendarServerAccess));
                } else if (vObject.getPropertyValue("X-CALENDARSERVER-ACCESS") != null) {
                    vObject.setPropertyValue("CLASS", getEventClass(vObject.getPropertyValue("X-CALENDARSERVER-ACCESS")));
                }
                if (!fromServer) {
                    // add organizer line to all events created in Exchange for active sync
                    if (vObject.getPropertyValue("ORGANIZER") == null) {
                        vObject.setPropertyValue("ORGANIZER", "MAILTO:" + email);
                    }
                    // set OWA allday flag
                    vObject.setPropertyValue("X-MICROSOFT-CDO-ALLDAYEVENT", isAllDay(vObject) ? "TRUE" : "FALSE");
                    vObject.setPropertyValue("X-MICROSOFT-CDO-BUSYSTATUS",
                            !"TRANSPARENT".equals(vObject.getPropertyValue("TRANSP")) ? "BUSY" : "FREE");
                    
                } else {
                    // remove organizer line for event without attendees for iPhone
                    if (getProperty("ATTENDEE") == null) {
                        vObject.setPropertyValue("ORGANIZER", null);
                    }
                    // TODO: handle transparent ?
                }

                fixAttendees(vObject, fromServer);

                // TODO handle BUSYSTATUS
                
                fixAlarm(vObject, fromServer);
            }
        }

    }

    private void fixAlarm(VObject vObject, boolean fromServer) {
        for (VObject vAlarm : vObject.vObjects) {
            if ("VALARM".equals(vAlarm.type)) {
                String action = vAlarm.getPropertyValue("ACTION");
                if (fromServer && "DISPLAY".equals(action)
                        // convert DISPLAY to AUDIO only if user defined an alarm sound
                        && Settings.getProperty("davmail.caldavAlarmSound") != null) {
                    // Convert alarm to audio for iCal
                    vAlarm.setPropertyValue("ACTION", "AUDIO");

                    if (vAlarm.getPropertyValue("ATTACH") == null) {
                        // Add defined sound into the audio alarm
                        VProperty vProperty = new VProperty("ATTACH", Settings.getProperty("davmail.caldavAlarmSound"));
                        vProperty.addParam("VALUE", "URI");
                        vAlarm.addProperty(vProperty);
                    }

                } else if (!fromServer && "AUDIO".equals(action)) {
                    // Use the alarm action that exchange (and blackberry) understand
                    // (exchange and blackberry don't understand audio actions)
                    vAlarm.setPropertyValue("ACTION", "DISPLAY");
                }
            }
        }
    }

    /**
     * Replace iCal4 (Snow Leopard) principal paths with mailto expression
     *
     * @param value attendee value or ics line
     * @return fixed value
     */
    protected String replaceIcal4Principal(String value) {
        if (value.contains("/principals/__uuids__/")) {
            return value.replaceAll("/principals/__uuids__/([^/]*)__AT__([^/]*)/", "mailto:$1@$2");
        } else {
            return value;
        }
    }

    private void fixAttendees(VObject vObject, boolean fromServer) {
        if (!fromServer) {
            if (vObject.properties != null) {
                for (VProperty property : vObject.properties) {
                    if ("ATTENDEE".equalsIgnoreCase(property.getKey())) {
                        property.setValue(replaceIcal4Principal(property.getValue()));

                        // ignore attendee as organizer
                        if (property.getValue().contains(email)) {
                            property.setValue(null);
                        }
                    }
                }

            }
        } else {
            // TODO patch RSVP
        }

    }

    /**
     * Convert X-CALENDARSERVER-ACCESS to CLASS.
     *
     * @return CLASS value
     */
    protected String getEventClass(String calendarServerAccess) {
        if ("PRIVATE".equalsIgnoreCase(calendarServerAccess)) {
            return "CONFIDENTIAL";
        } else if ("CONFIDENTIAL".equalsIgnoreCase(calendarServerAccess) || "RESTRICTED".equalsIgnoreCase(calendarServerAccess)) {
            return "PRIVATE";
        } else {
            return calendarServerAccess;
        }
    }

    /**
     * Convert CLASS to X-CALENDARSERVER-ACCESS.
     *
     * @return X-CALENDARSERVER-ACCESS value
     */
    protected String getCalendarServerAccess() {
        String eventClass = firstVevent.getPropertyValue("CLASS");
        if ("PRIVATE".equalsIgnoreCase(eventClass)) {
            return "CONFIDENTIAL";
        } else if ("CONFIDENTIAL".equalsIgnoreCase(eventClass)) {
            return "PRIVATE";
        } else {
            return eventClass;
        }
    }

}
