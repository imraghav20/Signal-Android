package org.thoughtcrime.securesms.database;


import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientFactory;
import org.thoughtcrime.securesms.recipients.RecipientFormattingException;
import org.thoughtcrime.securesms.recipients.Recipients;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.textsecure.util.Util;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedList;
import java.util.List;

import static org.whispersystems.textsecure.push.PushMessageProtos.PushMessageContent.AttachmentPointer;

public class GroupDatabase extends Database {
  private static final String TAG = GroupDatabase.class.getSimpleName();

  private static final String TABLE_NAME          = "groups";
  private static final String ID                  = "_id";
  private static final String GROUP_ID            = "group_id";
  private static final String TITLE               = "title";
  private static final String MEMBERS             = "members";
  private static final String AVATAR              = "avatar";
  private static final String AVATAR_ID           = "avatar_id";
  private static final String AVATAR_KEY          = "avatar_key";
  private static final String AVATAR_CONTENT_TYPE = "avatar_content_type";
  private static final String AVATAR_RELAY        = "avatar_relay";
  private static final String TIMESTAMP           = "timestamp";
  private static final String ACTIVE              = "active";

  public static final String CREATE_TABLE =
      "CREATE TABLE " + TABLE_NAME +
          " (" + ID + " INTEGER PRIMARY KEY, " +
          GROUP_ID + " TEXT, " +
          TITLE + " TEXT, " +
          MEMBERS + " TEXT, " +
          AVATAR + " BLOB, " +
          AVATAR_ID + " INTEGER, " +
          AVATAR_KEY + " BLOB, " +
          AVATAR_CONTENT_TYPE + " TEXT, " +
          AVATAR_RELAY + " TEXT, " +
          TIMESTAMP + " INTEGER, " +
          ACTIVE + " INTEGER DEFAULT 1);";

  public static final String[] CREATE_INDEXS = {
      "CREATE UNIQUE INDEX IF NOT EXISTS group_id_index ON " + TABLE_NAME + " (" + GROUP_ID + ");",
  };

  public GroupDatabase(Context context, SQLiteOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public GroupRecord getGroup(byte[] groupId) {
    Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, null, GROUP_ID + " = ?",
                                                               new String[] {GroupUtil.getEncodedId(groupId)},
                                                               null, null, null);

    Reader      reader = new Reader(cursor);
    GroupRecord record = reader.getNext();

    reader.close();
    return record;
  }

  public Recipients getGroupMembers(byte[] groupId) {
    List<String>    members    = getCurrentMembers(groupId);
    List<Recipient> recipients = new LinkedList<Recipient>();

    for (String member : members) {
      try {
        recipients.addAll(RecipientFactory.getRecipientsFromString(context, member, false)
                                          .getRecipientsList());
      } catch (RecipientFormattingException e) {
        Log.w("GroupDatabase", e);
      }
    }

    return new Recipients(recipients);
  }

  public void create(byte[] groupId, String owner, String title,
                     List<String> members, AttachmentPointer avatar,
                     String relay)
  {
    List<String> filteredMembers = new LinkedList<String>();
    String       localNumber     = TextSecurePreferences.getLocalNumber(context);

    if (!localNumber.equals(owner)) {
      filteredMembers.add(owner);
    }

    for (String member : members) {
      if (!member.equals(localNumber)) {
        filteredMembers.add(member);
      }
    }

    ContentValues contentValues = new ContentValues();
    contentValues.put(GROUP_ID, GroupUtil.getEncodedId(groupId));
    contentValues.put(TITLE, title);
    contentValues.put(MEMBERS, Util.join(filteredMembers, ","));

    if (avatar != null) {
      contentValues.put(AVATAR_ID, avatar.getId());
      contentValues.put(AVATAR_KEY, avatar.getKey().toByteArray());
      contentValues.put(AVATAR_CONTENT_TYPE, avatar.getContentType());
    }

    contentValues.put(AVATAR_RELAY, relay);
    contentValues.put(TIMESTAMP, System.currentTimeMillis());
    contentValues.put(ACTIVE, 1);

    databaseHelper.getWritableDatabase().insert(TABLE_NAME, null, contentValues);
  }

  public void update(byte[] groupId, String title, AttachmentPointer avatar) {
    ContentValues contentValues = new ContentValues();
    if (title != null) contentValues.put(TITLE, title);

    if (avatar != null) {
      contentValues.put(AVATAR_ID, avatar.getId());
      contentValues.put(AVATAR_CONTENT_TYPE, avatar.getContentType());
      contentValues.put(AVATAR_KEY, avatar.getKey().toByteArray());
    }

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues,
                                                GROUP_ID + " = ?",
                                                new String[] {GroupUtil.getEncodedId(groupId)});

    if (title != null) updateGroupRecipientTitle(groupId, title);
  }

  public void updateTitle(byte[] groupId, String title) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(TITLE, title);
    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, GROUP_ID +  " = ?",
                                                new String[] {GroupUtil.getEncodedId(groupId)});

    if (title != null) updateGroupRecipientTitle(groupId, title);
  }

  public void updateAvatar(byte[] groupId, Bitmap avatar) {
    updateAvatarInDatabase(groupId, BitmapUtil.toByteArray(avatar));
    updateGroupRecipientAvatar(groupId, avatar);
  }

  public void updateAvatar(byte[] groupId, byte[] avatar) {
    updateAvatarInDatabase(groupId, avatar);
    Bitmap bitmap = (avatar == null ? null : BitmapFactory.decodeByteArray(avatar, 0, avatar.length));
      updateGroupRecipientAvatar(groupId, bitmap);
  }

  private void updateAvatarInDatabase(byte[] groupId, byte[] avatar) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(AVATAR, avatar);

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, GROUP_ID +  " = ?",
                                                new String[] {GroupUtil.getEncodedId(groupId)});
  }

  public void add(byte[] id, String source, List<String> members) {
    List<String> currentMembers = getCurrentMembers(id);

    for (String currentMember : currentMembers) {
      if (currentMember.equals(source)) {
        List<String> concatenatedMembers = new LinkedList<String>(currentMembers);
        concatenatedMembers.addAll(members);

        ContentValues contents = new ContentValues();
        contents.put(MEMBERS, Util.join(concatenatedMembers, ","));
        contents.put(ACTIVE, 1);

        databaseHelper.getWritableDatabase().update(TABLE_NAME, contents, GROUP_ID + " = ?",
                                                    new String[] {GroupUtil.getEncodedId(id)});
      }
    }
  }

  public void remove(byte[] id, String source) {
    List<String> currentMembers = getCurrentMembers(id);
    currentMembers.remove(source);

    ContentValues contents = new ContentValues();
    contents.put(MEMBERS, Util.join(currentMembers, ","));

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contents, GROUP_ID + " = ?",
                                                new String[]{GroupUtil.getEncodedId(id)});
  }

  private List<String> getCurrentMembers(byte[] id) {
    Cursor cursor = null;

    try {
      cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, new String[] {MEMBERS},
                                                          GROUP_ID + " = ?",
                                                          new String[] {GroupUtil.getEncodedId(id)},
                                                          null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        return Util.split(cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS)), ",");
      }

      return new LinkedList<String>();
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public boolean isActive(byte[] id) {
    GroupRecord record = getGroup(id);
    return record != null && record.isActive();
  }

  public void setActive(byte[] id, boolean active) {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    ContentValues  values   = new ContentValues();
    values.put(ACTIVE, active ? 1 : 0);
    database.update(TABLE_NAME, values, GROUP_ID + " = ?", new String[] {GroupUtil.getEncodedId(id)});
  }


  public byte[] allocateGroupId() {
    try {
      byte[] groupId = new byte[16];
      SecureRandom.getInstance("SHA1PRNG").nextBytes(groupId);
      return groupId;
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }


  public static class Reader {

    private final Cursor cursor;

    public Reader(Cursor cursor) {
      this.cursor = cursor;
    }

    public GroupRecord getNext() {
      if (cursor == null || !cursor.moveToNext()) {
        return null;
      }

      return new GroupRecord(cursor.getString(cursor.getColumnIndexOrThrow(GROUP_ID)),
                             cursor.getString(cursor.getColumnIndexOrThrow(TITLE)),
                             cursor.getString(cursor.getColumnIndexOrThrow(MEMBERS)),
                             cursor.getBlob(cursor.getColumnIndexOrThrow(AVATAR)),
                             cursor.getLong(cursor.getColumnIndexOrThrow(AVATAR_ID)),
                             cursor.getBlob(cursor.getColumnIndexOrThrow(AVATAR_KEY)),
                             cursor.getString(cursor.getColumnIndexOrThrow(AVATAR_CONTENT_TYPE)),
                             cursor.getString(cursor.getColumnIndexOrThrow(AVATAR_RELAY)),
                             cursor.getInt(cursor.getColumnIndexOrThrow(ACTIVE)) == 1);
    }

    public void close() {
      if (this.cursor != null)
        this.cursor.close();
    }
  }

  public static class GroupRecord {

    private final String       id;
    private final String       title;
    private final List<String> members;
    private final byte[]       avatar;
    private final long         avatarId;
    private final byte[]       avatarKey;
    private final String       avatarContentType;
    private final String       relay;
    private final boolean      active;

    public GroupRecord(String id, String title, String members, byte[] avatar,
                       long avatarId, byte[] avatarKey, String avatarContentType,
                       String relay, boolean active)
    {
      this.id                = id;
      this.title             = title;
      this.members           = Util.split(members, ",");
      this.avatar            = avatar;
      this.avatarId          = avatarId;
      this.avatarKey         = avatarKey;
      this.avatarContentType = avatarContentType;
      this.relay             = relay;
      this.active            = active;
    }

    public byte[] getId() {
      try {
        return GroupUtil.getDecodedId(id);
      } catch (IOException ioe) {
        throw new AssertionError(ioe);
      }
    }

    public String getTitle() {
      return title;
    }

    public List<String> getMembers() {
      return members;
    }

    public byte[] getAvatar() {
      return avatar;
    }

    public long getAvatarId() {
      return avatarId;
    }

    public byte[] getAvatarKey() {
      return avatarKey;
    }

    public String getAvatarContentType() {
      return avatarContentType;
    }

    public String getRelay() {
      return relay;
    }

    public boolean isActive() {
      return active;
    }
  }

  private Recipient getGroupRecipient(byte[] groupId) {
    try {
      return RecipientFactory.getRecipientsFromString(context, GroupUtil.getEncodedId(groupId), true)
                             .getPrimaryRecipient();
    } catch (RecipientFormattingException e) {
      Log.w(TAG, e);
      return null;
    }
  }

  private void updateGroupRecipientTitle(byte[] groupId, String title) {
    Recipient groupRecipient = getGroupRecipient(groupId);
    Log.i(TAG, "updating group recipient title for recipient " + System.identityHashCode(groupRecipient));
    if (groupRecipient != null) groupRecipient.setName(title);
    else                        Log.w(TAG, "Couldn't update group title because recipient couldn't be found.");
  }

  private void updateGroupRecipientAvatar(byte[] groupId, Bitmap photo) {
    Recipient groupRecipient = getGroupRecipient(groupId);
    if (groupRecipient != null) groupRecipient.setContactPhoto(photo);
    else                        Log.w(TAG, "Couldn't update group title because recipient couldn't be found.");
  }

}
