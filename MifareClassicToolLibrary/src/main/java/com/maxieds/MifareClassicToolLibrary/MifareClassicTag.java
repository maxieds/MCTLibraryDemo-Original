package com.maxieds.MifareClassicToolLibrary;

import android.util.Log;
import android.content.Intent;
import android.app.PendingIntent;
import android.os.Build;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcA;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.content.Context;

import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import static com.maxieds.mifareclassictoollibrary.MifareClassicLibraryException.MFCLibraryExceptionType.*;

public class MifareClassicTag {

     private static final String TAG = MifareClassicTag.class.getSimpleName();

     public static final int MFCKEY_BYTE_SIZE = 6;

     public class MFCSector {

         public int sectorAddress;
         public int sectorSize;
         public int sectorBlockCount;
         public int sectorFirstBlock;
         public int sectorBytesPerBlock;
         public byte[] sectorAccessBits;
         public byte[] keyA, keyB;
         public byte[][] sectorBlockData;

         public MFCSector() {
             sectorAddress = sectorSize = sectorBlockCount = sectorFirstBlock = sectorBytesPerBlock = 0;
             sectorAccessBits = keyA = keyB = null;
             sectorBlockData = null;
         }

         public MFCSector FromTag(Tag nfcTag, int saddr) {
             MFCSector mfcSector = new MFCSector();
             MifareClassic mfcTag = MifareClassic.get(nfcTag);
             if(mfcTag == null) {
                 return null;
             }
             mfcSector.sectorAddress = saddr;
             mfcSector.sectorFirstBlock = mfcTag.sectorToBlock(mfcSector.sectorAddress);
             mfcSector.sectorBlockCount = mfcTag.getBlockCountInSector(mfcSector.sectorAddress);
             mfcSector.sectorBytesPerBlock = MifareClassic.BLOCK_SIZE;
             mfcSector.sectorSize = mfcSector.sectorBlockCount * mfcSector.sectorBytesPerBlock;
             return mfcSector;
         }

         public int ReadSector(Tag nfcTag, String[] trialKeysList) throws MifareClassicLibraryException {
              if(nfcTag == null) {
                   throw new MifareClassicLibraryException(NFCErrorException);
              }
              else if(trialKeysList == null || trialKeysList.length == 0) {
                   throw new MifareClassicLibraryException(InvalidKeysException);
              }
              MifareClassic mfcTag = MifareClassic.get(nfcTag);
              if(mfcTag == null) {
                   throw new MifareClassicLibraryException(NFCErrorException);
              }
              // try to "crack" the keys with a preset list of values before we proceed:
              boolean authedKeyA = false, authedKeyB = false;
              for(int kidx = 0; kidx < trialKeysList.length; kidx++) {
                   try {
                        byte[] activeKey = MCTUtils.HexStringToBytes(trialKeysList[kidx]);
                        if (!authedKeyA && mfcTag.authenticateSectorWithKeyA(sectorAddress, activeKey)) {
                             authedKeyA = true;
                             keyA = new byte[MFCKEY_BYTE_SIZE];
                             System.arraycopy(activeKey, 0, keyA, 0, MFCKEY_BYTE_SIZE);
                        }
                        if (!authedKeyB && mfcTag.authenticateSectorWithKeyB(sectorAddress, activeKey)) {
                             authedKeyB = true;
                             keyB = new byte[MFCKEY_BYTE_SIZE];
                             System.arraycopy(activeKey, 0, keyB, 0, MFCKEY_BYTE_SIZE);
                        }
                        if (authedKeyA && authedKeyB) {
                             break;
                        }
                   } catch(IOException ioe) {
                        Log.e(TAG, ioe.getStackTrace().toString());
                        throw new MifareClassicLibraryException(NFCErrorException);
                   }
              }
              try {
                   if (authedKeyA) {
                        mfcTag.authenticateSectorWithKeyA(sectorAddress, keyA);
                   }
                   else if (authedKeyB) {
                        mfcTag.authenticateSectorWithKeyB(sectorAddress, keyB);
                   }
              } catch(IOException ioe) {
                   Log.e(TAG, ioe.getStackTrace().toString());
                   throw new MifareClassicLibraryException(NFCErrorException);
              }
              int totalBytesRead = 0;
              sectorBlockData = new byte[sectorBlockCount][];
              for(int blk = 0; blk < sectorBlockCount; blk++) {
                   sectorBlockData[blk] = new byte[sectorBytesPerBlock];
                   try {
                        byte[] blockDataBytes = mfcTag.readBlock(blk);
                        if(blockDataBytes != null) {
                             System.arraycopy(blockDataBytes, 0, sectorBlockData[blk], 0, blockDataBytes.length);
                             totalBytesRead += blockDataBytes.length;
                             if(blk == sectorBlockCount - 1) { // in the trailer key and access bit block:
                                  sectorAccessBits = new byte[4];
                                  System.arraycopy(blockDataBytes, 6, sectorAccessBits, 0, 4);
                             }
                        }
                   } catch(Exception ioe) {
                        throw new MifareClassicLibraryException(PartialReadException);
                   }
              }
              return totalBytesRead;
         }

         public String GetAccessBytesDescription() {
             // TODO
             return null;
         }

     }

     private String mfcTagType;
     private int tagSize, tagSectorCount, tagBlockCount, tagBytesPerBlock;
     private byte[] mfcDumpImageData;
     private List<MFCSector> failedSectors;
     private String rfTechCaps, tagManufacturer;
     private String tagUID, tagATQA, tagSAK, tagATS;

     private boolean ResetParameters() {
         mfcTagType = "";
         tagSize = tagSectorCount = tagBlockCount = tagBytesPerBlock = 0;
         mfcDumpImageData = null;
         failedSectors = new ArrayList<MFCSector>();
         rfTechCaps = tagManufacturer = "";
         tagUID = tagATQA = tagSAK = tagATS = "";
         return true;
     }

     private MifareClassicTag() {
         ResetParameters();
     }

     public static final int MFC_FULL_SUPPORT = 0;
     public static final int NO_MFC_DEVICE_SUPPORT = -1;
     public static final int NO_MFC_TAG_SUPPORT = -2;
     public static final int MFC_TAG_ERROR = -3;

     public static int CheckMifareClassicSupport(Tag nfcTag, Context appContext) {
         if(nfcTag == null || appContext == null) {
             return MFC_TAG_ERROR;
         }
         else if(Arrays.asList(nfcTag.getTechList()).contains(MifareClassic.class.getName())) {
             // Device and tag support MIFARE Classic.
             return MFC_FULL_SUPPORT;
         }
         else {
             // Check if device does not support MIFARE Classic.
             // For doing so, check if the SAK of the tag indicate that
             // it's a MIFARE Classic tag.
             // See: https://www.nxp.com/docs/en/application-note/AN10834.pdf
             NfcA nfca = NfcA.get(nfcTag);
             byte sak = (byte) nfca.getSak();
             if ((sak >> 1 & 1) == 1) { // RFU.
                 return NO_MFC_TAG_SUPPORT;
             } else {
                 if ((sak >> 3 & 1) == 1) { // SAK bit 4 = 1?
                     if ((sak >> 4 & 1) == 1) { // SAK bit 5 = 1?
                         // MIFARE Classic 4k
                         // MIFARE SmartMX 4K
                         // MIFARE PlusS 4K SL1
                         // MIFARE PlusX 4K SL1
                         return NO_MFC_DEVICE_SUPPORT;
                     } else {
                         if ((sak & 1) == 1) { // SAK bit 1 = 1?
                             // MIFARE Mini
                             return NO_MFC_DEVICE_SUPPORT;
                         } else {
                             // MIFARE Classic 1k
                             // MIFARE SmartMX 1k
                             // MIFARE PlusS 2K SL1
                             // MIFARE PlusX 2K SL2
                             return NO_MFC_DEVICE_SUPPORT;
                         }
                     }
                 } else {
                     // Some MIFARE tag, but not Classic or Classic compatible.
                     return NO_MFC_TAG_SUPPORT;
                 }
             }
         }
     }

     public static MifareClassicTag Decode(Tag nfcTag, String[] keyData) throws MifareClassicLibraryException {
         if(nfcTag == null) {
             throw new MifareClassicLibraryException(NoTagException);
         }
         else if(keyData == null || keyData.length == 0) {
             throw new MifareClassicLibraryException(NoKeysFoundException);
         }
         else if(!CheckMFCKeys(keyData)) {
             throw new MifareClassicLibraryException(InvalidKeysException);
         }
         else if(!MifareClassicToolLibrary.Initialized()) {
             throw new MifareClassicLibraryException(GenericMFCException, "Uninitialized NFC tag data");
         }
         int mfcSupportCode = 0;
         if((mfcSupportCode = CheckMifareClassicSupport(nfcTag, MifareClassicToolLibrary.GetApplicationContext())) != 0) {
             throw new MifareClassicLibraryException(UnsupportedTagException);
         }
         MifareClassicTag mfcTagData = new MifareClassicTag();
         mfcTagData.ReadTagReservedData(nfcTag);
         mfcTagData.IdentifyTag(nfcTag, mfcSupportCode);
         mfcTagData.DumpTag(nfcTag, keyData);
         return mfcTagData;
     }

     public static MifareClassicTag Decode(Tag nfcTag) throws MifareClassicLibraryException {
         String[] defaultKeyData = new String[] {
                 MCTUtils.BytesToHexString(MifareClassic.KEY_DEFAULT),
                 MCTUtils.BytesToHexString(MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY),
                 MCTUtils.BytesToHexString(MifareClassic.KEY_NFC_FORUM)
         };
         return MifareClassicTag.Decode(nfcTag, defaultKeyData);
     }

     public boolean ReadTagReservedData(Tag nfcTag) throws MifareClassicLibraryException {
         tagUID = MCTUtils.BytesToHexString(nfcTag.getId());
         NfcA nfcaTag = NfcA.get(nfcTag);
         // Swap ATQA to match the common order like shown here:
         // http://nfc-tools.org/index.php?title=ISO14443A
         byte[] atqaBytes = nfcaTag.getAtqa();
         atqaBytes = new byte[] {atqaBytes[1], atqaBytes[0]};
         tagATQA = MCTUtils.BytesToHexString(atqaBytes);
         // SAK in big endian.
         byte[] sakBytes = new byte[] {
                 (byte) ((nfcaTag.getSak() >> 8) & 0xFF),
                 (byte) (nfcaTag.getSak() & 0xFF)
         };
         // Print the first SAK byte only if it is not 0.
         if (sakBytes[0] != 0x00) {
             tagSAK = MCTUtils.BytesToHexString(sakBytes);
         }
         else {
             tagSAK = MCTUtils.BytesToHexString(new byte[] { sakBytes[1] });
         }
         tagATS = "-";
         IsoDep isoTag = IsoDep.get(nfcTag);
         if (isoTag != null) {
             byte[] atsBytes = isoTag.getHistoricalBytes();
             if (atsBytes != null && atsBytes.length > 0) {
                 tagATS = MCTUtils.BytesToHexString(atsBytes);
             }
         }
         return true;
     }

     public boolean IdentifyTag(Tag nfcTag, int mfcSupportCode) throws MifareClassicLibraryException {
         // Identify tag type (by string).
         boolean successCode = true;
         int tagTypeResourceID = GetTagIdentifier(tagATQA, tagSAK, tagATS);
         if (tagTypeResourceID == R.string.tag_unknown && mfcSupportCode > NO_MFC_TAG_SUPPORT) {
             mfcTagType = MifareClassicToolLibrary.GetApplicationContext().getString(R.string.tag_unknown_mf_classic);
             successCode = false;
         }
         else {
             mfcTagType = MifareClassicToolLibrary.GetApplicationContext().getString(tagTypeResourceID);
         }
         // read in the logistical layout data (e.g., number of sectors, tag size, etc.):
         if(mfcSupportCode != 0) {
             successCode = false;
         }
         else {
             MifareClassic mfcTag = MifareClassic.get(nfcTag);
             tagSize = mfcTag.getSize();
             tagSectorCount = mfcTag.getSectorCount();
             tagBlockCount = mfcTag.getBlockCount();
             tagBytesPerBlock = MifareClassic.BLOCK_SIZE;
         }
         return successCode;
     }

     public boolean DumpTag(Tag nfcTag, String[] keyData) throws MifareClassicLibraryException {
          if(nfcTag == null) {
               throw new MifareClassicLibraryException(NFCErrorException);
          }
          else if(keyData == null || keyData.length == 0) {
               throw new MifareClassicLibraryException(InvalidKeysException);
          }
          mfcDumpImageData = new byte[tagSize];
          int mfcDumpDataArrayPos = 0;
          for(int sct = 0; sct < tagBlockCount; ) {
               MFCSector nextSector = new MFCSector();
               nextSector.FromTag(nfcTag, sct);
               int bytesRead = nextSector.ReadSector(nfcTag, keyData);
               if(bytesRead < nextSector.sectorSize) {
                    failedSectors.add(nextSector);
               }
               sct += nextSector.sectorSize;
               if(nextSector.sectorBlockData == null) {
                    mfcDumpDataArrayPos += nextSector.sectorSize;
                    continue;
               }
               for(int blk = 0; blk < nextSector.sectorBlockCount; blk++) {
                    if(nextSector.sectorBlockData[blk] != null) {
                         System.arraycopy(nextSector.sectorBlockData[blk], 0, mfcDumpImageData,
                                          mfcDumpDataArrayPos, nextSector.sectorBytesPerBlock);
                    }
                    mfcDumpDataArrayPos += nextSector.sectorBytesPerBlock;
               }
          }
          return true;
     }

    private int GetTagIdentifier(String atqa, String sak, String ats) {
        String prefix = "tag_";
        ats = ats.replace("-", "");
        // First check on ATQA + SAK + ATS.
        int ret = MifareClassicToolLibrary.GetApplicationContext().getResources().getIdentifier(
                prefix + atqa + sak + ats, "string", MifareClassicToolLibrary.GetApplicationContext().getPackageName());
        if (ret == 0) {
            // Check on ATQA + SAK.
            ret = MifareClassicToolLibrary.GetApplicationContext().getResources().getIdentifier(
                    prefix + atqa + sak, "string", MifareClassicToolLibrary.GetApplicationContext().getPackageName());
        }
        if (ret == 0) {
            // Check on ATQA.
            ret = MifareClassicToolLibrary.GetApplicationContext().getResources().getIdentifier(
                    prefix + atqa, "string", MifareClassicToolLibrary.GetApplicationContext().getPackageName());
        }
        if (ret == 0) {
            // No match found return "Unknown".
            return R.string.tag_unknown;
        }
        return ret;
    }

     public static boolean CheckMFCKeys(String[] keyDataList) {
         if(keyDataList == null) {
             return false;
         }
         for(int kidx = 0; kidx < keyDataList.length; kidx++) {
             if(!MCTUtils.IsHexAnd6Byte(keyDataList[kidx])) {
                 return false;
             }
         }
         return true;
     }

     public byte[] GetMFCDumpImageData() {
         return mfcDumpImageData;
     }

     public String GetTagType() {
         return mfcTagType;
     }

     public int GetTagSectors() {
         return tagSectorCount;
     }

     public int GetTagBlocks() {
         return tagBlockCount;
     }

     public int GetTagBytesPerBlock() {
         return tagBytesPerBlock;
     }

     public int GetTagSize() {
         return tagSize;
     }

     public List<MFCSector> GetFailedSectors() {
         return failedSectors;
     }

     public String GetRFTechCaps() {
         return rfTechCaps;
     }

     public String GetManufacturer() {
         return tagManufacturer;
     }

     public String GetTagUID() {
         return tagUID;
     }

     public int GetTagUIDSize() {
         return tagUID.length() / 2;
     }

     public String GetATQA() {
         return tagATQA;
     }

     public String GetSAK() {
         return tagSAK;
     }

     public String GetATS() {
         return tagATS;
     }

     public static String GetTagByteCountString(int byteCount) {
          if(byteCount == 1024) {
               return "1K";
          }
          else if(byteCount == 2048) {
               return "2K";
          }
          else if(byteCount == 4096) {
               return "4K";
          }
          else if(byteCount == 8192) {
               return "8K";
          }
          return String.valueOf(byteCount) + "B";
     }

     public String GetTagSizeSpecString() {
          String specString = String.format(Locale.US, "%s | %d sectors x %d blocks @ %s",
                                            GetTagByteCountString(GetTagSize()), tagSectorCount,
                                            tagBlockCount, tagBytesPerBlock);
          return specString;
     }

     public boolean ExportToHexFile(String outputFile) throws IOException {
          if(outputFile == null || mfcDumpImageData == null) {
               return false;
          }
          PrintWriter printWriter = new PrintWriter(outputFile, "UTF-8");
          for(int blk = 0; blk < tagBlockCount; blk += tagBytesPerBlock) {
               byte[] blockBytes = new byte[tagBytesPerBlock];
               System.arraycopy(mfcDumpImageData, blk, blockBytes, 0, tagBytesPerBlock);
               printWriter.print(MCTUtils.BytesToHexString(blockBytes));
          }
          printWriter.close();
          return true;
     }

     public boolean ExportToBinaryDumpFile(String outputFile) throws IOException {
          if(outputFile == null || mfcDumpImageData == null) {
               return false;
          }
          FileOutputStream outStream = new FileOutputStream(outputFile);
          outStream.write(mfcDumpImageData);
          outStream.close();
          return true;
     }

     public static final int MFCLASSIC1K_TAG_SIZE = 1024;
     public static final int MFCLASSIC_BLOCK_SIZE = 16;

     public static MifareClassicTag LoadMifareClassic1KFromResource(int resID) {

          if(!MifareClassicToolLibrary.Initialized()) {
               return null;
          }

          // initialize statically "known" fields for a MFC1K tag:
          MifareClassicTag mfcTagData = new MifareClassicTag();
          mfcTagData.mfcTagType = "Mifare Classic 1K (From Dump Image)";
          mfcTagData.tagSize = MFCLASSIC1K_TAG_SIZE;
          mfcTagData.tagSectorCount = 16;
          mfcTagData.tagBytesPerBlock = MFCLASSIC_BLOCK_SIZE;
          mfcTagData.tagBlockCount = mfcTagData.tagSize / mfcTagData.tagBytesPerBlock;
          mfcTagData.tagManufacturer = "Unknown";

          // initialize the tag data bytes from the dump image resource:
          Context appMainContext = MifareClassicToolLibrary.GetApplicationContext();
          try {
               InputStream rawFileStream = appContext.getResources().openRawResource(resID);
               mfcTagData.mfcDumpImageData = new byte[MFCLASSIC1K_TAG_SIZE];
               int bytesReadCount = 0;
               byte[] byteReadBuffer = new byte[MFCLASSIC_BLOCK_SIZE];
               while (bytesReadCount < MFCLASSIC1K_TAG_SIZE) {
                    int readByteCount = rawFileStream.read(byteReadBuffer, 0, MFCLASSIC1K_BLOCK_SIZE);
                    if (readByteCount < 0) {
                         break;
                    }
                    System.arraycopy(byteReadBuffer, 0, mfcTagData.mfcDumpImageData, bytesReadCount, readByteCount);
                    bytesReadCount += readByteCount;
               }
               if(bytesReadCount < MFCLASSIC1K_TAG_SIZE) {
                    Log.e(TAG, "ERROR: Only able to load " + bytesReadCount + " of " + MFCLASSIC1K_TAG_SIZE + "bytes from tag!");
                    return null;
               }
          } catch(IOException ioe) {
               Log.e(TAG, ioe.getStackTrace().toString());
               return null;
          }
          // load the rest of the first block (tag read-only) sector data for accounting:
          byte uidBytes[4];
          System.arraycopy(mfcTagData.mfcDumpImageData, 0, uidBytes, 0, 4);
          mfcTagData.tagUID = MCTUtils.BytesToHexString(uidBytes);
          byte sakByte = mfcTagData.mfcDumpImageData[5];
          mfcTagData.tagSAK = MCTUtils.BytesToHexString(new byte[] { sakByte });
          byte atqaBytes[2];
          System.arraycopy(mfcTagData.mfcDumpImageData, 6, atqaBytes, 0, 2);
          mfcTagData.tagATQA = MCTUtils.BytesToHexString(atqaBytes);
          mfcTagData.tagATS = "Unknown ATS";
          byte manuBytes[8];
          System.arraycopy(mfcTagData.mfcDumpImageData, 8, manuBytes, 0, 8);
          mfcTagData.tagManufacturer = MCTUtils.BytesToHexString(manuBytes);

          return mfcTagData;

     }

}