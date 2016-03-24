// Copyright 2011 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.notes;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.enterprise.connector.logging.NDC;
import com.google.enterprise.connector.notes.client.NotesDatabase;
import com.google.enterprise.connector.notes.client.NotesDocument;
import com.google.enterprise.connector.notes.client.NotesDocumentCollection;
import com.google.enterprise.connector.notes.client.NotesEmbeddedObject;
import com.google.enterprise.connector.notes.client.NotesItem;
import com.google.enterprise.connector.notes.client.NotesRichTextItem;
import com.google.enterprise.connector.notes.client.NotesSession;
import com.google.enterprise.connector.notes.client.NotesView;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.spi.SpiConstants.ActionType;

import java.io.File;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class NotesCrawlerThread extends Thread {
  private static final String CLASS_NAME = NotesCrawlerThread.class.getName();
  private static final Logger LOGGER = Logger.getLogger(CLASS_NAME);

  static final String META_FIELDS_PREFIX = "x.";

  private final NotesConnector nc;
  private final NotesConnectorSession ncs;
  private NotesSession ns = null;
  private NotesDatabase cdb = null;
  @VisibleForTesting
  NotesDocument templateDoc = null;
  @VisibleForTesting
  NotesDocument formDoc = null;
  @VisibleForTesting
  NotesDocumentCollection formsdc = null;
  private String openDbRepId = "";
  private NotesDatabase srcdb = null;
  private NotesView crawlQueue = null;

  @VisibleForTesting
  List<MetaField> metaFields;

  NotesCrawlerThread(NotesConnector Connector, NotesConnectorSession Session) {
    LOGGER.finest("NotesCrawlerThread being created.");

    nc = Connector;
    ncs = Session;
  }

  // Since we are multi-threaded, each thread has its own objects
  // which are not shared.  Hence the calling thread must pass
  // the Domino objects to this method.
  @VisibleForTesting
  static synchronized NotesDocument getNextFromCrawlQueue(
      NotesSession ns, NotesView crawlQueue) {
    try {
      crawlQueue.refresh();
      NotesDocument nextDoc = crawlQueue.getFirstDocument();
      if (nextDoc == null) {
        return null;
      }
      LOGGER.finer("Prefetching document");
      nextDoc.replaceItemValue(NCCONST.NCITM_STATE, NCCONST.STATEINCRAWL);
      nextDoc.save(true);

      return nextDoc;
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Aborting crawl", e);
    }
    return null;
  }

  protected void loadTemplateDoc(String TemplateName)
      throws RepositoryException {
    final String METHOD = "loadTemplate";
    LOGGER.entering(CLASS_NAME, METHOD);

    // Is a template document all ready loaded?
    if (null != templateDoc) {
      // Is this the one we need?
      if (TemplateName.equals(
              templateDoc.getItemValueString(NCCONST.TITM_TEMPLATENAME))) {
        return;
      }
      Util.recycle(templateDoc, formsdc, formDoc);
      templateDoc = null;
      formsdc = null;
      formDoc = null;
    }
    NotesView vw = cdb.getView(NCCONST.VIEWTEMPLATES);
    templateDoc = vw.getDocumentByKey(TemplateName, true);
    formsdc = templateDoc.getResponses();

    // Parse any configured MetaFields once per template load.
    Vector templateMetaFields =
        templateDoc.getItemValue(NCCONST.TITM_METAFIELDS);
    metaFields = new ArrayList<MetaField>(templateMetaFields.size());
    for (Object o : templateMetaFields) {
      metaFields.add(new MetaField((String) o));
    }
    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.finest("template MetaFields: '" + templateMetaFields
          + "'; parsed MetaFields: " + metaFields);
    }
    vw.recycle();
  }

  protected void loadForm(String FormName) throws RepositoryException {
    final String METHOD = "loadForm";
    LOGGER.entering(CLASS_NAME, METHOD);

    if (null != formDoc) {
      if (FormName == formDoc.getItemValueString(NCCONST.FITM_LASTALIAS)) {
        return;
      }
      formDoc.recycle();
      formDoc = null;
    }
    if (null == formsdc) {
      return;
    }
    formDoc = formsdc.getFirstDocument();
    while (null != formDoc) {
      String formDocName = formDoc.getItemValueString(NCCONST.FITM_LASTALIAS);
      if (formDocName.equals(FormName)) {
        return;
      }
      NotesDocument prevDoc = formDoc;
      formDoc = formsdc.getNextDocument(prevDoc);
      prevDoc.recycle();
    }
  }

  /*
   *   Some comments on Domino.
   *
   *   Reader security is only enforced in Domino if there are
   *   Readers fields on the document and they are non-blank
   *
   *   Authors fields also provide read access to the document if
   *   document level security is enforced.  However if there are
   *   authors fields, but not any non-blank readers fields,
   *   document level security will not be enforced.
   */
  protected void setDocumentReaderNames(NotesDocument crawlDoc,
      NotesDocument srcDoc) throws RepositoryException {
    final String METHOD = "setDocumentReaderNames";
    LOGGER.entering(CLASS_NAME, METHOD);

    Vector<?> allItems = srcDoc.getItems();
    try {
      Vector<String> authorReaders = new Vector<String>();
      boolean hasReaders = false;
      Vector<Integer> authorItems = new Vector<Integer>();

      // Find the Readers field(s), if any. There can be more
      // than one Readers field.
      for (int i = 0; i < allItems.size(); i++) {
        NotesItem item = (NotesItem) allItems.elementAt(i);
        if (item.isReaders()) {
          boolean hasCurrentItemReaders =
              copyValues(item, authorReaders, "readers");
          hasReaders = hasCurrentItemReaders || hasReaders;
        } else if (item.isAuthors()) {
          authorItems.add(i);
        }
      }
      // If there are Readers, add any Authors to the Readers list
      // for AuthZ purposes. With no Readers, database security applies.
      if (hasReaders && authorItems.size() > 0) {
        for (Integer i : authorItems) {
          copyValues((NotesItem) allItems.elementAt(i),
              authorReaders, "authors");
        }
      }

      LOGGER.log(Level.FINEST, "Document readers for {0} are {1}",
          new Object[] {
            crawlDoc.getItemValueString(NCCONST.ITM_DOCID), authorReaders});
      if (authorReaders.size() > 0) {
        crawlDoc.replaceItemValue(NCCONST.NCITM_DOCAUTHORREADERS,
            authorReaders);
        crawlDoc.replaceItemValue(NCCONST.NCITM_DOCREADERS, authorReaders);
      }
    } finally {
      srcDoc.recycle(allItems);
    }
  }

  private boolean copyValues(NotesItem item, Vector<String> destination,
      String description) throws RepositoryException {
    Vector values = item.getValues();
    int count = 0;
    if (null != values) {
      LOGGER.log(Level.FINEST, "Adding {0} {1}",
          new Object[] { description, values });
      for (; count < values.size(); count++) {
        destination.add(values.elementAt(count).toString().toLowerCase());
      }
    }
    return count > 0;
  }

  // This function will set google security fields for the document
  protected void setDocumentSecurity(NotesDocument crawlDoc)
      throws RepositoryException {
    final String METHOD = "setDocumentSecurity";
    LOGGER.entering(CLASS_NAME, METHOD);

    String AuthType = crawlDoc.getItemValueString(NCCONST.NCITM_AUTHTYPE);

    crawlDoc.replaceItemValue(NCCONST.ITM_ISPUBLIC,
        String.valueOf(AuthType.equals(NCCONST.AUTH_NONE)));
  }

  protected void evaluateField(NotesDocument crawlDoc, NotesDocument srcDoc,
      String formula, String ItemName, String Default)
      throws RepositoryException {
    final String METHOD = "evaluateField";
    LOGGER.entering(CLASS_NAME, METHOD);

    String Result = null;
    try {
      LOGGER.log(Level.FINEST, "Evaluating formula for item {0} : src is: {1}",
          new Object[] { ItemName, formula });
      Vector<?> VecEvalResult = ns.evaluate(formula, srcDoc);
      // Make sure we don't get an empty vector or an empty string.
      if (VecEvalResult != null && VecEvalResult.size() > 0) {
        Result = VecEvalResult.elementAt(0).toString();
        LOGGER.log(Level.FINEST, "Evaluating formula result is: {0}", Result);
      }
      if (Strings.isNullOrEmpty(Result)) {
        Result = Default;
      }
    } catch (RepositoryException e) {
      // TODO(jlacey): Should this set Result = Default instead?
      LOGGER.log(Level.SEVERE, "Skipping {0}: Unable to evaluate formula: {1}",
          new Object[] { ItemName, formula });
    } finally {
      crawlDoc.replaceItemValue(ItemName, Result);
    }
    LOGGER.exiting(CLASS_NAME, METHOD);
  }


  // TODO: Consider mapping other fields so they can be used for
  // dynamic navigation.  This could be an configurable option.

  // This function will map the fields from the source database
  // to the crawl doc using the configuration specified in
  // formDoc
  protected void mapFields(NotesDocument crawlDoc, NotesDocument srcDoc)
      throws RepositoryException {
    final String METHOD = "mapFields";
    LOGGER.entering(CLASS_NAME, METHOD);

    // Copy the standard fields
    String NotesURL = srcDoc.getNotesURL();
    String HttpURL = getHTTPURL(crawlDoc);
    crawlDoc.replaceItemValue(NCCONST.ITM_DOCID, HttpURL);
    crawlDoc.replaceItemValue(NCCONST.ITM_DISPLAYURL, HttpURL);
    crawlDoc.replaceItemValue(NCCONST.ITM_GMETAFORM,
        srcDoc.getItemValueString(NCCONST.ITMFORM));
    crawlDoc.replaceItemValue(NCCONST.ITM_LASTMODIFIED,
        srcDoc.getLastModified());
    crawlDoc.replaceItemValue(NCCONST.ITM_GMETAWRITERNAME, srcDoc.getAuthors());
    crawlDoc.replaceItemValue(NCCONST.ITM_GMETALASTUPDATE,
        srcDoc.getLastModified());
    crawlDoc.replaceItemValue(NCCONST.ITM_GMETACREATEDATE, srcDoc.getCreated());

    // We need to generate the title and description using a formula
    String formula;
    // When there is no form configuration use the config from the template
    if (formDoc != null) {
      formula = formDoc.getItemValueString(NCCONST.FITM_SEARCHRESULTSFORMULA);
    } else {
      formula = templateDoc.getItemValueString(NCCONST.TITM_SEARCHRESULTSFIELDS);
    }
    evaluateField(crawlDoc, srcDoc, formula, NCCONST.ITM_TITLE, "");

    // Again..when there is no form configuration use the config
    // from the template
    if (formDoc != null) {
      formula = formDoc.getItemValueString(NCCONST.FITM_DESCRIPTIONFORMULA);
    } else {
      formula = templateDoc.getItemValueString(NCCONST.TITM_DESCRIPTIONFIELDS);
    }
    evaluateField(crawlDoc, srcDoc, formula, NCCONST.ITM_GMETADESCRIPTION, "");
    LOGGER.exiting(CLASS_NAME, METHOD);

    // DO NOT MAP THIS FIELD - it will force the GSA to try and crawl this URL
    // crawlDoc.replaceItemValue(NCCONST.ITM_SEARCHURL, HttpURL);
  }

  @VisibleForTesting
  void mapMetaFields(NotesDocument crawlDoc, NotesDocument srcDoc)
      throws RepositoryException {
    final String METHOD = "mapMetaFields";
    LOGGER.entering(CLASS_NAME, METHOD);
    for (MetaField mf : metaFields) {
      NotesItem item = null;
      try {
        if (null == mf.getFieldName()) {
          if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "Skipping null fieldname");
          }
          continue;
        }
        String configForm = mf.getFormName();
        if (null != configForm) {
          String docForm = srcDoc.getItemValueString(NCCONST.ITMFORM);
          if (!configForm.equalsIgnoreCase(docForm)) {
            if (LOGGER.isLoggable(Level.FINEST)) {
              LOGGER.log(Level.FINEST,
                  "Skipping metafields because configured form {0} does not "
                  + "match doc form {1}",
                  new Object[] { configForm, docForm });
            }
            continue;
          }
        }
        if (!srcDoc.hasItem(mf.getFieldName())) {
          if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST,
                "Source doc does not have field: {0}", mf.getFieldName());
          }
          continue;
        }
        // If there are multiple items with the same name (not a
        // common Notes occurrence), only the first item will be
        // mapped.
        item = srcDoc.getFirstItem(mf.getFieldName());
        if (null == item.getValues()) {
          if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST,
                "Source doc does not have value for: {0}", mf.getFieldName());
          }
          continue;
        }
        Object content = item;
        if (item.getType() == NotesItem.RICHTEXT) {
          content = item.getText(2 * 1024);
        }
        if (crawlDoc.hasItem(META_FIELDS_PREFIX + mf.getMetaName())) {
          LOGGER.log(Level.WARNING,
              "Mapping meta fields: meta field {0} already exists in crawl doc",
              mf.getMetaName());
          // If multiple Notes fields are mapped to the same meta
          // field, only the first mapping will be used.
          continue;
        }
        crawlDoc.replaceItemValue(META_FIELDS_PREFIX + mf.getMetaName(),
            content);
        if (LOGGER.isLoggable(Level.FINEST)) {
          LOGGER.log(Level.FINEST, "Mapped meta field : {0}{1} = {2}",
              new Object[] { META_FIELDS_PREFIX, mf.getMetaName(), content });
        }
      } catch (RepositoryException e) {
        LOGGER.log(Level.WARNING, "Error mapping MetaField " + mf, e);
      } finally {
        Util.recycle(item);
      }
    }
    LOGGER.exiting(CLASS_NAME, METHOD);
  }

  protected String getHTTPURL(NotesDocument crawlDoc)
      throws RepositoryException {
    // Get the domain name associated with the server
    String server = crawlDoc.getItemValueString(NCCONST.NCITM_SERVER);
    String domain = ncs.getDomain(server);

    return String.format("http://%s%s/%s/0/%s", server, domain,
        crawlDoc.getItemValueString(NCCONST.NCITM_REPLICAID),
        crawlDoc.getItemValueString(NCCONST.NCITM_UNID));
  }

  protected String getContentFields(NotesDocument srcDoc)
      throws RepositoryException {
    final String METHOD = "getContentFields";
    LOGGER.entering(CLASS_NAME, METHOD);

    // TODO:  Handle stored forms
    StringBuffer content = new StringBuffer();
    // If we have a form document then we have a specified list
    // of fields to index
    if (null != formDoc) {
      Vector<?> v = formDoc.getItemValue(NCCONST.FITM_FIELDSTOINDEX);
      for (int i = 0; i < v.size(); i++) {
        String fieldName = v.elementAt(i).toString();
        // Fields beginning with $ are reserved fields in Domino
        // Do not index the Form field ever
        if ((fieldName.charAt(0) == '$') ||
            (fieldName.equalsIgnoreCase("form"))) {
          continue;
        }
        content.append("\n");
        NotesItem tmpItem = srcDoc.getFirstItem(fieldName);
        if (null != tmpItem) {
          // Must use getText to get more than 64k of text
          content.append(tmpItem.getText(2 * 1024 * 1024));
          tmpItem.recycle();
        }
      }
      LOGGER.exiting(CLASS_NAME, METHOD);
      return content.toString();
    }

    // Otherwise we will index all allowable fields
    LinkedHashSet<String> items = new LinkedHashSet<String>();
    Vector <?> vi = srcDoc.getItems();
    try {
      for (int j = 0; j < vi.size(); j++) {
        NotesItem itm = (NotesItem) vi.elementAt(j);
        String ItemName = itm.getName();
        if ((ItemName.charAt(0) == '$')
            || (ItemName.equalsIgnoreCase("form"))) {
          continue;
        }
        int type = itm.getType();
        switch (type) {
          case NotesItem.TEXT:
          case NotesItem.NUMBERS:
          case NotesItem.DATETIMES:
          case NotesItem.RICHTEXT:
          case NotesItem.NAMES:
          case NotesItem.AUTHORS:
          case NotesItem.READERS:
            items.add(ItemName);
            break;
          default:
            break;
        }
      }

      for (String item : items) {
        content.append("\n");
        NotesItem tmpItem = srcDoc.getFirstItem(item);
        if (null != tmpItem) {
          // Must use getText to get more than 64k of text
          content.append(tmpItem.getText(2 * 1024 * 1024));
        }
      }
    } finally {
      Util.recycle(srcDoc,vi);
    }
    LOGGER.exiting(CLASS_NAME, METHOD);
    return content.toString();
  }

  protected boolean prefetchDoc(NotesDocument crawlDoc) {
    final String METHOD = "prefetchDoc";
    LOGGER.entering(CLASS_NAME, METHOD);

    String NotesURL = null;
    try {
      NotesURL = crawlDoc.getItemValueString(NCCONST.ITM_GMETANOTESLINK);
      LOGGER.log(Level.FINER, "Prefetching document {0}" + NotesURL);

      // Get the template for this document
      loadTemplateDoc(crawlDoc.getItemValueString(NCCONST.NCITM_TEMPLATE));
      if (null == templateDoc) {
        LOGGER.log(Level.FINER, "No template found for document {0}",
            crawlDoc.getItemValueString(NCCONST.ITM_GMETANOTESLINK));
        return false;
      }

      // Check to see if the database we all ready have open is
      // the right one by comparing replicaids
      String crawlDocDbRepId = crawlDoc.getItemValueString(
          NCCONST.NCITM_REPLICAID);
      if (!crawlDocDbRepId.contentEquals(openDbRepId)) {
        // Different ReplicaId - Recycle and close the old database
        if (srcdb != null) {
          srcdb.recycle();
          srcdb= null;
        }
        // Open the new database
        srcdb = ns.getDatabase(null, null);
        srcdb.openByReplicaID(crawlDoc.getItemValueString(
                NCCONST.NCITM_SERVER), crawlDocDbRepId);
        openDbRepId = crawlDocDbRepId;
      }

      // Load our source document
      NotesDocument srcDoc = srcdb.getDocumentByUNID(
          crawlDoc.getItemValueString(NCCONST.NCITM_UNID));
      // Get the form configuration for this document
      loadForm(srcDoc.getItemValueString(NCCONST.ITMFORM));
      if (null == formDoc) {
        LOGGER.log(Level.FINER,
            "No form definition found.  Using template definition " +
            "to process document {0}", NotesURL);
      }

      setDocumentReaderNames(crawlDoc, srcDoc);
      setDocumentSecurity(crawlDoc);

      mapFields(crawlDoc, srcDoc);
      mapMetaFields(crawlDoc, srcDoc);

      // Process the attachments associated with this document
      // When there are multiple attachments with the same name
      // Lotus Notes automatically generates unique names for next document
      Vector<?> va = ns.evaluate("@AttachmentNames", srcDoc);
      Vector<String> docIds = new Vector<String>();

      NotesItem attachItems = crawlDoc.replaceItemValue(
          NCCONST.ITM_GMETAATTACHMENTS, "");
      for (int i = 0; i < va.size(); i++) {
        String attachName = va.elementAt(i).toString();

        if (attachName.length() == 0) {
          continue;
        }
        String xtn;
        int period = attachName.lastIndexOf(".");
        if (period == -1) {
          xtn = "";
        } else {
          xtn = attachName.substring(period + 1);
        }
        if (!ncs.isExcludedExtension(xtn.toLowerCase())) {
          String docId = createAttachmentDoc(crawlDoc, srcDoc,
              attachName, ncs.getMimeType(xtn));
          if (docId != null) {
            attachItems.appendToTextList(attachName);
            docIds.add(docId);
          } else {
            LOGGER.log(Level.FINER,
                "Attachment document was not created for {0}", attachName);
          }
        } else {
          LOGGER.log(Level.FINER, "Excluding attachment in {0} : {1}",
              new Object[] { NotesURL, attachName });
        }
      }
      crawlDoc.replaceItemValue(NCCONST.ITM_GMETAALLATTACHMENTS, va);
      crawlDoc.replaceItemValue(NCCONST.ITM_GMETAATTACHMENTDOCIDS, docIds);

      // Get our content after processing attachments
      // We don't want the document content in the attachment docs
      // Our content must be stored as non-summary rich text to
      // avoid the 32/64K limits in Domino
      NotesRichTextItem contentItem = crawlDoc.createRichTextItem(
          NCCONST.ITM_CONTENT);
      String content = getContentFields(srcDoc);
      contentItem.appendText(content);
      contentItem.setSummary(false);

      // Update the status of the document to be fetched.
      crawlDoc.replaceItemValue(NCCONST.ITM_ACTION, ActionType.ADD.toString());
      srcDoc.recycle();

      // Check attachments against H2 database and create delete requests for
      // attachments which no longer exist in source document.
      NotesDocId notesDocId =
          new NotesDocId(crawlDoc.getItemValueString(NCCONST.ITM_DOCID));
      enqueue(notesDocId, docIds);

      return true;
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Error prefetching document " + NotesURL, e);
      return false;
    } finally {
      LOGGER.exiting(CLASS_NAME, METHOD);
    }
  }

  /**
   * Create delete requests for attachments which no longer exist in the
   * source document.
   * 
   * @param notesId google:docid of the parent document
   * @param attachIds hashes of current attachment names
   */
  void enqueue(NotesDocId notesId, Vector<String> attachIds) {
    LOGGER.log(Level.FINEST, "Send delete requests for attachments which "
        + "no longer exist in source document [UNID: {0}]", notesId);
    Set<String> curAttachIds = new HashSet<String>(attachIds);

    NotesDocumentManager docMgr = ncs.getNotesDocumentManager();
    Connection conn = null;
    try {
      conn = docMgr.getDatabaseConnection();
      Set<String> allAttachIds = docMgr.getAttachmentIds(conn,
          notesId.getDocId(), notesId.getReplicaId());
      for (String attachId : allAttachIds) {
        if (!curAttachIds.contains(attachId)) {
          LOGGER.log(Level.FINEST, "{0} attachment is in cache but not in "
              + "source document, send delete request to GSA", attachId);
          try {
            // Send deletion for each attachment
            String attachmentUrl = String.format(NCCONST.SITM_ATTACHMENTDOCID,
                notesId.toString(), attachId);
            createDeleteRequest(attachmentUrl);
          } catch (RepositoryException e) {
            LOGGER.log(Level.WARNING,
                "Failed to create delete request for attachment: " + attachId);
          }
        }
      }
    } catch (SQLException e) {
      LOGGER.log(Level.SEVERE, "Unable to connect to H2 database", e);
    } finally {
      if (conn != null) {
        docMgr.releaseDatabaseConnection(conn);
      }
    }
  }

  private void createDeleteRequest(String googleDocId)
      throws RepositoryException {
    LOGGER.log(Level.FINEST, "Send deletion request to GSA for {0}",
        googleDocId);
    NotesDocument deleteReq = cdb.createDocument();
    deleteReq.appendItemValue(NCCONST.ITMFORM, NCCONST.FORMCRAWLREQUEST);
    deleteReq.replaceItemValue(NCCONST.ITM_ACTION,
        ActionType.DELETE.toString());
    deleteReq.replaceItemValue(NCCONST.ITM_DOCID, googleDocId);
    deleteReq.replaceItemValue(NCCONST.NCITM_STATE, NCCONST.STATEFETCHED);
    deleteReq.save(true);
    deleteReq.recycle();
  }

  /**
   * Creates a document for an attachment in the GSA Configuration database.  If
   * the file size is exceeding the limit or the MIME type is not supported,
   * only metadata and the attachment file name will be indexed.
   * 
   * @param crawlDoc document being crawled in the Crawl Queue view
   * @param srcDoc source document where the attachment is located
   * @param AttachmentName string file name without encoding
   * @param MimeType string MIME type computed from file extension
   * @return attachment document ID string if the attachment document is created
   *         and its content will be indexed.
   *         null string if the attachment document is not created and its
   *         content will not be indexed.
   * @throws RepositoryException if embedded object is not accessible
   */
  @VisibleForTesting
  String createAttachmentDoc(NotesDocument crawlDoc, NotesDocument srcDoc,
      String AttachmentName, String MimeType) throws RepositoryException {
    final String METHOD = "createAttachmentDoc";
    LOGGER.entering(CLASS_NAME, METHOD);
    NotesEmbeddedObject eo = null;
    NotesDocument attachDoc = null;

    try {
      // Error access the attachment
      eo = srcDoc.getAttachment(AttachmentName);

      if (eo == null) {
        LOGGER.log(Level.FINER, "Attachment could not be accessed {0}",
            AttachmentName);
        return null;
      }

      if (eo.getType() != NotesEmbeddedObject.EMBED_ATTACHMENT) {
        // The object is not an attachment - could be an OLE object or link
        LOGGER.log(Level.FINER, "Ignoring embedded object {0}", AttachmentName);
        eo.recycle();
        return null;
      }

      // Don't send attachments larger than the limit
      if (eo.getFileSize() > ncs.getMaxFileSize()) {
        LOGGER.log(Level.FINER,
            "Attachment larger than the configured limit and content " +
            "will not be sent. {0}", AttachmentName);
      }

      attachDoc = cdb.createDocument();
      crawlDoc.copyAllItems(attachDoc, true);

      // Store the filename of this attachment in the attachment crawl doc.
      attachDoc.replaceItemValue(NCCONST.ITM_GMETAATTACHMENTFILENAME,
          AttachmentName);
      attachDoc.save();

      // Compute display URL
      String encodedAttachmentName;
      try {
        encodedAttachmentName = URLEncoder.encode(AttachmentName, "UTF-8");
      } catch (Exception e) {
        attachDoc.recycle();
        eo.recycle();
        return null;
      }
      String AttachmentURL = String.format(NCCONST.SITM_ATTACHMENTDISPLAYURL,
          getHTTPURL(crawlDoc), encodedAttachmentName);
      attachDoc.replaceItemValue(NCCONST.ITM_DISPLAYURL, AttachmentURL);
      LOGGER.log(Level.FINEST, "Attachment display url: {0}", AttachmentURL);

      // Compute docid
      String attachNameHash = Util.hash(AttachmentName);
      if (attachNameHash == null) {
        return null;
      }
      String docURL = String.format(NCCONST.SITM_ATTACHMENTDOCID,
          getHTTPURL(crawlDoc), attachNameHash);
      attachDoc.replaceItemValue(NCCONST.ITM_DOCID, docURL);
      LOGGER.log(Level.FINEST, "Attachment document docid: {0}", docURL);

      // Only if we have a supported mime type and file size is not exceeding
      // the limit do we send the content, or only metadata and file name will
      // be sent.
      if ((0 != MimeType.length()) &&
          eo.getFileSize() <= ncs.getMaxFileSize()) {
        attachDoc.replaceItemValue(NCCONST.ITM_MIMETYPE, MimeType);
        String attachmentPath = getAttachmentFilePath(crawlDoc, attachNameHash);
        eo.extractFile(attachmentPath);
        attachDoc.replaceItemValue(NCCONST.ITM_CONTENTPATH, attachmentPath);
      } else {
        // Not a supported attachment so sending meta data only
        // with the filename as content
        attachDoc.replaceItemValue(NCCONST.ITM_CONTENT, AttachmentName);
        attachDoc.replaceItemValue(NCCONST.ITM_MIMETYPE,
            NCCONST.DEFAULT_MIMETYPE);
      }
      eo.recycle();

      // Set the state of this document to be fetched
      attachDoc.replaceItemValue(NCCONST.ITM_ACTION, ActionType.ADD.toString());
      attachDoc.replaceItemValue(NCCONST.NCITM_STATE, NCCONST.STATEFETCHED);
      attachDoc.save();
      attachDoc.recycle();
      LOGGER.exiting(CLASS_NAME, METHOD);
      return attachNameHash;
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE,
          "Error pre-fetching attachment: " + AttachmentName +
          " in document: " + srcDoc.getNotesURL(), e);
      Util.recycle(eo);
      if (null != attachDoc) {
        attachDoc.replaceItemValue(NCCONST.NCITM_STATE, NCCONST.STATEERROR);
        attachDoc.save();
        attachDoc.recycle();
      }
      return null;
    }
  }

  // This function will generate an unique file path for an attachment object.
  // Consider the situation where a document is updated twice and
  // appears in the submitq twice In this case, the first submit
  // will delete the doc.  The second submit will then send an
  // empty doc So we must use the UNID of the crawl request to
  // generate the unique filename
  private String getAttachmentFilePath(NotesDocument crawlDoc,
      String attachName) throws RepositoryException {
    String dirName = String.format("%s/attachments/%s/%s",
        ncs.getSpoolDir(),
        cdb.getReplicaID(),
        crawlDoc.getUniversalID());
    new File(dirName).mkdirs();
    String FilePath = String.format("%s/%s", dirName, attachName);
    //TODO:  Ensure that FilePath is a valid Windows filepath
    return FilePath;
  }

  @VisibleForTesting
  void connectQueue() throws RepositoryException {
    if (null == ns) {
      ns = ncs.createNotesSession();
    }
    if (null == cdb) {
      cdb = ns.getDatabase(ncs.getServer(), ncs.getDatabase());
    }
    if (crawlQueue == null) {
      crawlQueue = cdb.getView(NCCONST.VIEWCRAWLQ);
    }
  }


  /*
   * We accumulate objects as pre-fetch documents
   * De-allocate these in reverse order
   */
  private void disconnectQueue()  {
    final String METHOD = "disconnectQueue";
    LOGGER.entering(CLASS_NAME, METHOD);
    try {
      Util.recycle(templateDoc, formDoc, formsdc, srcdb, crawlQueue, cdb);
      templateDoc = null;
      formDoc = null;
      formsdc = null;
      openDbRepId = "";
      srcdb = null;
      crawlQueue = null;
      cdb = null;

      if (null != ns) {
        ncs.closeNotesSession(ns);
      }
      ns = null;
    } finally {
      LOGGER.exiting(CLASS_NAME, METHOD);
    }
  }

  @Override
  public void run() {
    NDC.push("Crawler " + nc.getGoogleConnectorName());
    final String METHOD = "run";
    int exceptionCount = 0;
    LOGGER.entering(CLASS_NAME, METHOD);
    NotesPollerNotifier npn = ncs.getNotifier();
    while (nc.getShutdown() == false) {
      try {
        // Only get from the queue if there is more than 300MB in the
        // spool directory
        File spoolDir = new File(ncs.getSpoolDir());
        LOGGER.log(Level.FINE,
            "Spool free space is {0}", spoolDir.getFreeSpace());
        if (spoolDir.getFreeSpace()/1000000 < 300) {
          LOGGER.log(Level.WARNING,
              "Insufficient space in spool directory to process " +
              "new documents.  Need at least 300MB.");
          npn.waitForWork();
          LOGGER.log(Level.FINE,
              "Crawler thread resuming after spool directory had " +
              "insufficient space.");
          continue;
        }
        LOGGER.log(Level.FINEST, "Connecting to crawl queue.");
        connectQueue();
        NotesDocument crawlDoc = getNextFromCrawlQueue(ns, crawlQueue);
        if (crawlDoc == null) {
          LOGGER.log(Level.FINE, 
              "{0}: Crawl queue is empty. Crawler thread sleeping.", getName());
          // If we have finished processing the queue shutdown our connections
          disconnectQueue();
          npn.waitForWork();
          LOGGER.log(Level.FINE,
              "{0} Crawler thread resuming after crawl queue was empty.",
              getName());
          continue;
        }
        if (prefetchDoc(crawlDoc)) {
          crawlDoc.replaceItemValue(NCCONST.NCITM_STATE, NCCONST.STATEFETCHED);
        } else  {
          crawlDoc.replaceItemValue(NCCONST.NCITM_STATE, NCCONST.STATEERROR);
        }
        crawlDoc.save(true);
        crawlDoc.recycle();
      } catch (Exception e) {
        LOGGER.log(Level.SEVERE, CLASS_NAME, e);
        // Lets say the server we are connected to goes down
        // while we are crawling We don't want to fill up the
        // logs with errors so go to sleep after 5 exceptions
        exceptionCount++;

        // If we run into an exception we should close our session.
        disconnectQueue();

        if (exceptionCount > 5) {
          LOGGER.log(Level.WARNING,
              "Too many exceptions.  Crawler thread sleeping.");
          npn.waitForWork();
          LOGGER.log(Level.WARNING,
              "Crawler thread resuming after too many exceptions " +
              "were encountered.");
        }
      }
    }
    disconnectQueue();
    LOGGER.log(Level.FINE, "Connector shutdown - NotesCrawlerThread exiting.");
    LOGGER.exiting(CLASS_NAME, METHOD);
  }

  @VisibleForTesting
  static class MetaField {
    private static final Pattern formFieldMetaPattern =
        Pattern.compile("\\A(.+)===([^=]+)=([^=]+)\\z");
    private static final Pattern fieldMetaPattern =
        Pattern.compile("\\A([^=]+)=([^=]+)\\z");
    private static final Pattern fieldPattern =
        Pattern.compile("\\A([^=]+)\\z");

    private String formName;
    private String fieldName;
    private String metaName;

    MetaField(String configString) {
      if (configString == null) {
        return;
      }
      configString = configString.trim();
      if (configString.length() == 0) {
        return;
      }

      Matcher matcher = formFieldMetaPattern.matcher(configString);
      if (matcher.matches()) {
        formName = matcher.group(1);
        fieldName = matcher.group(2);
        metaName = matcher.group(3);
        return;
      }
      matcher = fieldMetaPattern.matcher(configString);
      if (matcher.matches()) {
        fieldName = matcher.group(1);
        metaName = matcher.group(2);
        return;
      }
      matcher = fieldPattern.matcher(configString);
      if (matcher.matches()) {
        fieldName = matcher.group(1);
        metaName = fieldName;
        return;
      }
      LOGGER.log(Level.WARNING,
          "Unable to parse custom meta field definition; skipping: {0}",
          configString);
    }

    String getFormName() {
      return formName;
    }

    String getFieldName() {
      return fieldName;
    }

    String getMetaName() {
      return metaName;
    }

    @Override
    public String toString() {
      return "[form: " + formName + "; field: " + fieldName
          + "; meta: " + metaName + "]";
    }
  }
}
