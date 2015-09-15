package csefsck;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class csefsck {

	public static int superBlockNum =0;
	public static int devId = 20;
	public static int blockSize = 4096;

	public static void main(String[] args) {	
		ArrayList<Integer> occupiedBlockList = new ArrayList<Integer>();
		ArrayList<Integer> freeBlockList = new ArrayList<Integer>();
		ArrayList<Integer> dirList = new ArrayList<Integer>();
		ArrayList<Integer> fileList= new ArrayList<Integer>();
		ArrayList<Integer> fileIndexAndDataList = new ArrayList<Integer>();

		SuperBlock superBlock =   ReadSuperBlock(superBlockNum);
		if(superBlock.devId == devId){
			ValidateDateTimeSuperBlock(superBlock);

			ValidateDirectories(superBlock.root,superBlock.root,dirList,fileList,fileIndexAndDataList);
			occupiedBlockList.addAll(dirList);
			occupiedBlockList.addAll(fileList);
			occupiedBlockList.addAll(fileIndexAndDataList);
			occupiedBlockList.add(superBlock.root);
			for(int i=superBlock.freeStart ;i<=superBlock.freeEnd; i++){
				occupiedBlockList.add(i);
			}
			//3.Validating and correcting the free block list is performed in this method
			ValidateFreeBlocks(superBlock,occupiedBlockList,freeBlockList);
			//7.File data size validation is performed inside this method
			ValidateDataBlockSize(fileList, freeBlockList,superBlock);
			System.out.println("File System checker has completed its operations.");
		}else{
			System.out.println("The file system intended to check is not found!!");
		}
		System.out.println("Exiting...");
	}

	/*This method is recursively implemented. It will traverse from the root directory to the lower most leaf directory or file
	 * and validate all the points from 2 to 6 given in the requirement.
	 * */
	private static void ValidateDirectories(int parentDirNum,int dirNum,ArrayList<Integer> dirList
			, ArrayList<Integer> fileList,ArrayList<Integer> fileDataList) {
		StringBuilder sb = ReadFile(dirNum);
		Map<String, String> dictionary = new HashMap<String, String>();
		String[] splitTwo = sb.substring(1, sb.length()-3).split(": ");
		String[] props = splitTwo[0].split(", ");
		for(String prop:props ){		    	
			String[] propSubs =prop.trim().split(":");
			String fileDictProp ="filename_to_inode_dict";
			if(propSubs[0].equals(fileDictProp)){
				dictionary.put(propSubs[0], splitTwo[1].trim());
			}else{
				dictionary.put(propSubs[0], propSubs[1].trim());
			}

		}
		Directory directory = new Directory(dictionary.get("size")
				,dictionary.get("uid")
				,dictionary.get("gid")
				,dictionary.get("mode")
				,dictionary.get("atime")
				,dictionary.get("ctime")
				,dictionary.get("mtime")
				,dictionary.get("linkcount")
				,dictionary.get("filename_to_inode_dict"));
		dirList.add(dirNum);
		//2. DateTimeValidation on directory
		ValidateDateTimeForDirectory(directory,dirNum);
		Map<String, String> fileDict = new HashMap<String, String>();
		Map<String, String> dirDict = new HashMap<String, String>();
		String[] fileInodeDicts = directory.filename_to_inode_dict.substring(1, directory.filename_to_inode_dict.length()-1).split(", ");
		for(String fileOrDir:fileInodeDicts ){		    	
			String[] propSubs =fileOrDir.trim().split(":");
			String dirType="d";
			if(propSubs[0].equals(dirType))
				dirDict.put(propSubs[1], propSubs[2].trim());
			else
				fileDict.put(propSubs[1], propSubs[2].trim());

		}
		//ERROR TYPE 4:Each directory contains . and .. and their block numbers are correct is validated in this method
		ValidateCurrentAndParentDirectory(parentDirNum,dirNum,dirDict,directory);
		//ERROR TYPE 5:Each directory’s link count matches the number of links in the filename_to_inode_dict is validated in this method
		ValidateLinkCountForDirectory(fileInodeDicts,directory,dirNum);
		for(String file:fileDict.values()){						
			ValidateFiles(Integer.parseInt(file),fileList,fileDataList);
		}
		for (Map.Entry<String, String> entry : dirDict.entrySet())
		{
			String dot=".";
			String dotdot ="..";
			if(!entry.getKey().equals(dot) && !entry.getKey().equals(dotdot)){				
				ValidateDirectories(dirNum,Integer.parseInt(entry.getValue()),dirList,fileList,fileDataList);
			}
		}



	}
	/*This method Validates the link count in a directory and corrects if its wrong
	 * */
	private static void ValidateLinkCountForDirectory(String[] fileInodeDicts,
			Directory directory,int dirNum) {
		if(directory.linkcount != fileInodeDicts.length){
			directory.linkcount = fileInodeDicts.length;
			WriteToFile(directory.toString(), dirNum);
			System.out.println("MESSAGE TYPE 5:For Directory number:"+dirNum+"linkcount is corrected");
		}

	}
	/*This method validates the dot and dot dot values of a directory and corrects it if its wrong.
	 * */
	private static void ValidateCurrentAndParentDirectory(int parentDirNum,
			int dirNum, Map<String, String> dirDict,Directory directory) {
		boolean isDirty =false;
		if(dirDict.get(".")==null || dirDict.get(".")==""){
			isDirty=true;
			if(directory.filename_to_inode_dict != null || directory.filename_to_inode_dict != ""){
				directory.filename_to_inode_dict = directory.filename_to_inode_dict+", d:.:"+dirNum;
			}else{
				directory.filename_to_inode_dict = "d:.:"+dirNum;
			}
			System.out.println("MESSAGE TYPE 4:For DirectoryNumber:"+dirNum+" the current directory value is corrected");
			dirDict.put(".", String.valueOf(dirNum));
		}else if(!dirDict.get(".").equals(String.valueOf(dirNum))){
			isDirty=true;
			directory.filename_to_inode_dict = directory.filename_to_inode_dict.replace("d:.:"+dirDict.get("."), "d:.:"+dirNum);
			System.out.println("MESSAGE TYPE 4:For DirectoryNumber:"+dirNum+" the current directory value is corrected");
			dirDict.put(".", String.valueOf(dirNum));

		}
		if(dirDict.get("..")==null || dirDict.get("..")==""){
			isDirty=true;
			if(directory.filename_to_inode_dict != null || directory.filename_to_inode_dict != ""){
				directory.filename_to_inode_dict = directory.filename_to_inode_dict+", d:..:"+parentDirNum;
			}else{
				directory.filename_to_inode_dict = "d:..:"+parentDirNum;
			}
			System.out.println("MESSAGE TYPE 4:For DirectoryNumber:"+dirNum+" the parent directory value is corrected");
			dirDict.put("..", String.valueOf(parentDirNum));
		}else if(!dirDict.get("..").equals(String.valueOf(parentDirNum))){
			isDirty=true;
			directory.filename_to_inode_dict = directory.filename_to_inode_dict.replace("d:..:"+dirDict.get(".."), "d:..:"+parentDirNum);
			System.out.println("MESSAGE TYPE 4:For DirectoryNumber:"+dirNum+" the parent directory value is corrected");
			dirDict.put("..", String.valueOf(parentDirNum));
		}
		if(isDirty){
			WriteToFile(directory.toString(), dirNum);
		}
	}
	/* Validate of file related tasks like datetime validation and checking if indirect value is correct are not are performed here.
	 * */
	private static void ValidateFiles(int fileNum,ArrayList<Integer> fileList,ArrayList<Integer> fileDataList) {		
		StringBuilder sb = ReadFile(fileNum);
		Map<String, String> dictionary = new HashMap<String, String>();		
		String[] props = sb.substring(1, sb.length()-3).split(", ");
		for(String prop:props ){
			if(prop.contains(" ")){
				String[] twoProps = prop.split(" ");
				for(String twoProp:twoProps){
					String[] propSubs =twoProp.trim().split(":");

					dictionary.put(propSubs[0], propSubs[1].trim());
				}
			}else{
				String[] propSubs =prop.trim().split(":");

				dictionary.put(propSubs[0], propSubs[1].trim());
			}

		}
		MyFile file = new MyFile(dictionary.get("size")
				,dictionary.get("uid")
				,dictionary.get("gid")
				,dictionary.get("mode")
				,dictionary.get("linkcount")
				,dictionary.get("atime")
				,dictionary.get("ctime")
				,dictionary.get("mtime")
				,dictionary.get("indirect")
				,dictionary.get("location"));
		fileList.add(fileNum);
		//ERROR TYPE 6:If the data contained in a location pointer is an array, that indirect is one is validated and fixed in the below method
		ValidateIndirectionInFile(file,fileNum,fileDataList);
		//ERROR TYPE 2:Data time validation is performed on file and fixed if any issue is found
		ValidateDateTimeForFile(file,fileNum);		

	}
	/*Returns the file inode value  as MyFile according to the filenumber passed.
	 * */
	private static MyFile ReadFileStructure(int fileNum){
		StringBuilder sb = ReadFile(fileNum);
		Map<String, String> dictionary = new HashMap<String, String>();		
		String[] props = sb.substring(1, sb.length()-3).split(", ");
		for(String prop:props ){
			if(prop.contains(" ")){
				String[] twoProps = prop.split(" ");
				for(String twoProp:twoProps){
					String[] propSubs =twoProp.trim().split(":");

					dictionary.put(propSubs[0], propSubs[1].trim());
				}
			}else{
				String[] propSubs =prop.trim().split(":");

				dictionary.put(propSubs[0], propSubs[1].trim());
			}

		}
		MyFile file = new MyFile(dictionary.get("size")
				,dictionary.get("uid")
				,dictionary.get("gid")
				,dictionary.get("mode")
				,dictionary.get("linkcount")
				,dictionary.get("atime")
				,dictionary.get("ctime")
				,dictionary.get("mtime")
				,dictionary.get("indirect")
				,dictionary.get("location"));
		return file;
	}
	/*Validates block size is correctly used by files if not blocks are freed .
	 * */
	private static void ValidateDataBlockSize(ArrayList<Integer> fileList, ArrayList<Integer> freeBlockList,SuperBlock superBlock) {
		for(Integer fileNum:fileList){
			MyFile file = ReadFileStructure(fileNum);
			if(file.indirect==1){
				StringBuilder indexBlockPointers=ReadFile(file.location);
				String[] dataLocations = indexBlockPointers.substring(0,indexBlockPointers.length()-2).split(", ");
				ArrayList<String> freeDataLocations = new ArrayList<String>();		
				ArrayList<String> usedDataLocations = new ArrayList<String>();
				StringBuilder totalFileData = new StringBuilder();
				int totalDataSize=0;
				for(String dataLoc:dataLocations){
					freeDataLocations.add(dataLoc);
					StringBuilder sb = ReadFile(Integer.parseInt(dataLoc));
					totalFileData.append(sb.toString());
					totalDataSize+=sb.length();
				}
				int iterationsCount = (int)Math.ceil(totalDataSize/blockSize); 
				if(iterationsCount ==0 || ((iterationsCount ==1) && (totalDataSize%blockSize==0))){
					
					file.indirect =0;
					int indexBlockToBeFreed = file.location;
					file.location =Integer.parseInt(dataLocations[0]);
					WriteToFile(totalFileData.toString(),Integer.parseInt( dataLocations[0]));
					usedDataLocations.add(dataLocations[0]);
					freeDataLocations.remove(0);
					WriteToFile(file.toString(), fileNum);
					//Free the index block
					AddFreeBlock(indexBlockToBeFreed, freeBlockList);
					for(String freeDataLoc: freeDataLocations){
						AddFreeBlock(Integer.parseInt(freeDataLoc), freeBlockList);
					}
					System.out.println("MESSAGE TYPE 7:Indirection not required so index block: "+indexBlockToBeFreed+" is freed to free block list and indirect is changed to 0 in file inode");
				}else{
					int numberOfBlocksUsed=0;
					if(iterationsCount < dataLocations.length){
						System.out.println("MESSAGE TYPE 7:More than required index block pointers where used so its freed to free block list and index block is updated with changes");
						int j=blockSize;
						for(int i=0;i<iterationsCount;i++){
							int startByte=(i*blockSize) + 1;
							if(startByte == 1)
								startByte =0;
							WriteToFile(totalFileData.substring(startByte,(i+1)*j), Integer.parseInt(dataLocations[i])); 
							freeDataLocations.remove(0);
							usedDataLocations.add(dataLocations[i]);
							numberOfBlocksUsed++;
						}
						if(totalDataSize%blockSize !=0){
							int startByte=(iterationsCount*blockSize)+1;
							if(iterationsCount ==0){
								startByte=0;
							}
							WriteToFile(totalFileData.substring(startByte,totalFileData.length()), Integer.parseInt(dataLocations[iterationsCount]));
							freeDataLocations.remove(0);
							usedDataLocations.add(dataLocations[iterationsCount]);
							numberOfBlocksUsed++;
						}
						//Add the blocks which are freed to the free block list
						int numberBlocksTobeRemoved = dataLocations.length - numberOfBlocksUsed;
						for(int i=0;i<numberBlocksTobeRemoved;i++){						
							//TODO: Datatime should be in seconds						
							AddFreeBlock(Integer.parseInt( freeDataLocations.get(i)),freeBlockList);
						}
						//Write the indexblock pointers which are newly formed after freeing unnecessary ones
						StringBuilder newIndexPointers = new StringBuilder();
						for(String indexPointer: usedDataLocations){
							newIndexPointers.append(indexPointer+", ");
						}
						WriteToFile(newIndexPointers.substring(0,newIndexPointers.length()-2), file.location);

					}
				}
			}
		}

	}
	/*Returns if a string is integer or not
	 * */
	public static boolean IsInteger( String input )
	{
		try
		{
			Integer.parseInt( input );
			return true;
		}
		catch( Exception  ex)
		{
			return false;
		}
	}

	/**
	 * @param file
	 * Checks whether a file is valid to have indirect =1 . Corrects if there are mistakes.
	 */
	private static void ValidateIndirectionInFile(MyFile file,int fileNum,ArrayList<Integer> fileDataList) {		
		StringBuilder locationContent = ReadFile(file.location);
		//Map<String, String> indexDict = new HashMap<String, String>();	
		//TODO: Check if -2 is required here 
		String[] dataLocations = locationContent.substring(0,locationContent.length()-2).split(", ");
		boolean isValidIndex=true;
		if(dataLocations.length >0 ){			
			for(String dataLoc:dataLocations){
				if(!IsInteger(dataLoc)){
					isValidIndex=false;
					break;
				}
			}

		}
		if(isValidIndex){
			if(file.indirect != 1){
				file.indirect = 1;
				WriteToFile(file.toString(), fileNum);

				System.out.println("MESSAGE TYPE 6:Indirection value is corrected to 1 in fileNum: "+fileNum);	
			}
			fileDataList.add(file.location);
			for(String dataBlock:dataLocations){
				fileDataList.add(Integer.parseInt(dataBlock));
			}
		}else{
			if(file.indirect != 0){
				file.indirect = 0;				
				WriteToFile(file.toString(), fileNum);
				System.out.println("MESSAGE TYPE 6:Indirection value is corrected to 0 in fileNum: "+fileNum);
			}
			fileDataList.add(file.location);

		}
	}
	//Checks that the date time value is not in the future if so its corrected to current time.
	private static DateValidation ValidateDateTime(long dateTime){
		DateValidation dateValidation = new DateValidation();
		Date currentTime = new Date();	    
		Date creationTime =new Date(dateTime/1000);	  
		if(creationTime.compareTo(currentTime)>0){		    	
			dateValidation.modifiedDateTime = currentTime.getTime()*1000;
			dateValidation.isModified =true;	    	
		}
		return dateValidation;
	}
	/**
	 * @param superBlock
	 * Datetime value in superblock is validated and corrected if required.
	 */
	private static void ValidateDateTimeSuperBlock(SuperBlock superBlock) {
		//DateValidation
		DateValidation dateValidation = ValidateDateTime(superBlock.creationTime);
		if(dateValidation.isModified){
			superBlock.creationTime=dateValidation.modifiedDateTime;
			WriteToFile(superBlock.toString(),superBlockNum);
			System.out.println("MESSAGE TYPE 2:CreationTime is corrected in SuperBlock");
		}
	}
	//Validates datetime parameters of directory.
	private static void ValidateDateTimeForDirectory(Directory directory,int dirNum) {
		boolean isDirty=false;
		DateValidation aTimeValidation =ValidateDateTime(directory.atime);
		DateValidation cTimeValidation =ValidateDateTime(directory.ctime);
		DateValidation mTimeValidation =ValidateDateTime(directory.mtime);
		if(aTimeValidation.isModified){
			directory.atime=aTimeValidation.modifiedDateTime;	
			isDirty=true;
		}
		if(cTimeValidation.isModified){
			directory.ctime=cTimeValidation.modifiedDateTime;	
			isDirty=true;
		}
		if(mTimeValidation.isModified){
			directory.mtime=mTimeValidation.modifiedDateTime;	
			isDirty=true;
		}
		if(isDirty){
			System.out.println("MESSAGE TYPE 2:DateTime Value is corrected in DirNum: "+dirNum);
			WriteToFile(directory.toString(), dirNum);
		}
	}
	//Validates datetime for file.
	private static void ValidateDateTimeForFile(MyFile file,int fileNum) {
		boolean isDirty=false;
		DateValidation aTimeValidation =ValidateDateTime(file.atime);
		DateValidation cTimeValidation =ValidateDateTime(file.ctime);
		DateValidation mTimeValidation =ValidateDateTime(file.mtime);
		if(aTimeValidation.isModified){
			file.atime=aTimeValidation.modifiedDateTime;	
			isDirty=true;
		}
		if(cTimeValidation.isModified){
			file.ctime=cTimeValidation.modifiedDateTime;	
			isDirty=true;
		}
		if(mTimeValidation.isModified){
			file.mtime=mTimeValidation.modifiedDateTime;	
			isDirty=true;
		}
		if(isDirty){
			System.out.println("MESSAGE TYPE 2:DateTime Value is corrected in Filnum: "+fileNum);
			WriteToFile(file.toString(), fileNum);
		}

	}
	/**
	 * This method finds the occupied blocks list and writes the free block list with rest of the values between 27 and (maxBlock size-1)
	 */
	private static void ValidateFreeBlocks(SuperBlock superBlock,ArrayList<Integer> occupiedBlockList,ArrayList<Integer> freeBlockList){
		try{
			if(superBlock!=null){				
				for(int i=1;i<superBlock.maxBlocks;i++){
					if(!occupiedBlockList.contains(i))
						freeBlockList.add(i);
				}
				freeBlockList.sort(null);
				int[][] fuseDataList=new int[25][400];

				for(int i=superBlock.freeStart;i<=superBlock.freeEnd;i++){
					int j=0;
					StringBuilder sb=new StringBuilder();
					for(Integer freeBlock: freeBlockList){
						int freeBlockNum =(int)(Math.floor((freeBlock/400) + 1));
						if( freeBlockNum == i){
							fuseDataList[freeBlockNum-1][j]=freeBlock;							
							sb.append(freeBlock+", ");
							j++;
						}
					}
					WriteToFile(sb.substring(0, sb.length()-2), i);
				}
				System.out.println("Validation:3: FreeBlock List is validated and corrected if required");

			}

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 


	}
	//This method returns the next free block from the freeblock list
	private static int GetNextFreeBlock(SuperBlock superBlock,ArrayList<Integer> freeBlockList){
		int freeBlock=-1;
		for(int i=superBlock.freeStart;i<=superBlock.freeEnd;i++){
			StringBuilder sb = ReadFile(i);
			if(sb!=null){
				String[] freeBlocks = sb.toString().split(", ");
				if(freeBlocks.length >0){
					freeBlock = Integer.parseInt(freeBlocks[0]);
					RemoveFreeBlock(i, freeBlocks,freeBlockList);
					break;
				}
			}
		}
		return freeBlock;
	}

	/**
	 * @param fileNum
	 * @param freeBlocks
	 * Removes the free block from free block list once it is used up
	 */
	private static void RemoveFreeBlock(int fileNum, String[] freeBlocks,ArrayList<Integer> freeBlockList) {
		String[] newFreeBlocks = Arrays.copyOfRange(freeBlocks, 1, freeBlocks.length);
		StringBuilder sbNew=new StringBuilder();
		for(String newFreeBlock: newFreeBlocks){
			sbNew.append(newFreeBlock+", ");
		}
		WriteToFile(sbNew.substring(0, sbNew.length()-2), fileNum);
		freeBlockList.remove(0);
	}
	/**
	 * @param fileNum
	 * @param freeBlocks
	 * Free block passed as parameter is added into the right free block list.
	 */
	private static void AddFreeBlock(int blockNumToAdd,ArrayList<Integer> freeBlockList) {
		int freeBlockFileNum =(int)(Math.floor((blockNumToAdd/400) + 1));
		StringBuilder sb = ReadFile(freeBlockFileNum);
		if(sb!=null){			
			sb.append(", "+blockNumToAdd);
		}else{
			sb=new StringBuilder();
			sb.append(blockNumToAdd);
		}
		WriteToFile(sb.toString(), freeBlockFileNum);
		freeBlockList.add(blockNumToAdd);
		freeBlockList.sort(null);
	}
	/**
	 * This method is a generic implementation of reading a file which returns a string.
	 * @param j
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	private static StringBuilder ReadFile(int j) {
		try {
			Path path = FileSystems.getDefault().getPath("fusedata");			
			File file = new File(path.toString()+"\\fusedata."+j);
			BufferedReader br = new BufferedReader(new FileReader(file));
			StringBuilder sb = new StringBuilder();
			String line = br.readLine();

			while (line != null) {
				sb.append(line);
				sb.append(System.lineSeparator());
				line = br.readLine();
			}
			br.close();
			return sb;
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Validates the device id and corrects it if its wrong
	 * Validates creation time and corrects it if its in future
	 */
	private static SuperBlock ReadSuperBlock(int superBlockNum) {

		try {
			//Read from SuperBlock
			Path path = FileSystems.getDefault().getPath("fusedata");			
			File file = new File(path.toString()+"\\fusedata."+superBlockNum);
			BufferedReader br = new BufferedReader(new FileReader(file));
			StringBuilder sb = new StringBuilder();
			String line = br.readLine();

			while (line != null) {
				sb.append(line);
				sb.append(System.lineSeparator());
				line = br.readLine();
			}
			Map<String, String> dictionary = new HashMap<String, String>();
			String[] props = sb.substring(1, sb.length()-3).split(", ");
			for(String prop:props ){		    	
				String[] propSubs =prop.trim().split(":");
				dictionary.put(propSubs[0], propSubs[1].trim());

			}
			SuperBlock superBlock=new SuperBlock(dictionary.get("creationTime"),
					dictionary.get("mounted"),
					dictionary.get("devId"),
					dictionary.get("freeStart"),
					dictionary.get("freeEnd"),
					dictionary.get("root"),
					dictionary.get("maxBlocks"));



			br.close();

			return superBlock;
		} catch ( IOException e ) {
			e.printStackTrace();
			return null;

		}

	}

	/**
	 * Generic method to write to a file. The content that is passed is written into the file number specified.
	 */
	private static void WriteToFile(String fileContent,int fileNum) {
		try {
			Path path = FileSystems.getDefault().getPath("fusedata");			
			File file = new File(path.toString()+"\\fusedata."+fileNum);
			BufferedWriter output = new BufferedWriter(new FileWriter(file));		   
			output.write(fileContent);
			output.close();
		} catch ( IOException e ) {
			e.printStackTrace();
		}
	}



}

class DateValidation {
	boolean isModified;
	long modifiedDateTime;
	

}

class Directory {
	int size;//verify if long
	int uid;
	int gid;
	int mode;
	long atime;
	long ctime;
	long mtime;
	int linkcount;
	String filename_to_inode_dict;
	
	public Directory(String size,
			String uid,
			String gid,
			String mode,
			String atime,
			String ctime,
			String mtime,
			String linkcount,
			String fileToInodeDict) {
		 this.size=Integer.parseInt(size);
		this.uid=Integer.parseInt(uid);
		 this.gid=Integer.parseInt(gid);
		 this.mode=Integer.parseInt(mode);
		 this.atime=Long.parseLong(atime);
		 this.ctime=Long.parseLong(ctime);
		this.mtime=Long.parseLong(mtime);
		this.linkcount=Integer.parseInt(linkcount);
		this.filename_to_inode_dict=fileToInodeDict;
	}
	@Override public String toString() {
	    StringBuilder result = new StringBuilder();
	    
	    result.append("{");
	    result.append("size:" + this.size );
	    result.append(", uid:" + this.uid );
	    result.append(", gid:" + this.gid );
	    result.append(", mode:" + this.mode );
	    result.append(", atime:" + this.atime );
	    result.append(", ctime:" + this.ctime );
	    result.append(", mtime:" + this.mtime );
	    result.append(", linkcount:" + this.linkcount );
	    result.append(", filename_to_inode_dict: " + this.filename_to_inode_dict );	   
	    result.append("}");

	    return result.toString();
	  }

}

class MyFile {
	int size;//verify if long
	int uid;
	int gid;
	int mode;
	int linkcount;
	long atime;
	long ctime;
	long mtime;
	int indirect;
	int location;

	public MyFile(String size,
			String uid,
			String gid,
			String mode,
			String linkcount,
			String atime,
			String ctime,
			String mtime,
			String indirect,
			String location) {
		this.size=Integer.parseInt(size);
		this.uid=Integer.parseInt(uid);
		this.gid=Integer.parseInt(gid);
		this.mode=Integer.parseInt(mode);
		this.linkcount=Integer.parseInt(linkcount);
		this.atime=Long.parseLong(atime);
		this.ctime=Long.parseLong(ctime);
		this.mtime=Long.parseLong(mtime);
		this.indirect=Integer.parseInt(indirect);
		this.location=Integer.parseInt(location);		
	}
	@Override public String toString() {
		StringBuilder result = new StringBuilder();

		result.append("{");
		result.append("size:" + this.size );
		result.append(", uid:" + this.uid );
		result.append(", gid:" + this.gid );
		result.append(", mode:" + this.mode );
		result.append(", linkcount:" + this.linkcount );
		result.append(", atime:" + this.atime );
		result.append(", ctime:" + this.ctime );
		result.append(", mtime:" + this.mtime );
		result.append(", indirect:" + this.indirect );
		result.append(" location:" + this.location );
		result.append("}");

		return result.toString();
	}
}

class SuperBlock {

	long creationTime;
	int mounted;
	int devId;
	int freeStart;
	int freeEnd;
	int root;
	int maxBlocks;
	public SuperBlock(){
		
	}
	
	
	public SuperBlock(String creationTime,
			String mounted,
			String devId,
			String freeStart,
			String freeEnd,
			String root,
			String maxBlocks) {
		 this.creationTime=Long.parseLong(creationTime);
		this.mounted=Integer.parseInt(mounted);
		 this.devId=Integer.parseInt(devId);
		 this.freeStart=Integer.parseInt(freeStart);
		 this.freeEnd=Integer.parseInt(freeEnd);
		 this.root=Integer.parseInt(root);
		this.maxBlocks=Integer.parseInt(maxBlocks);
	}
	
@Override public String toString() {
    StringBuilder result = new StringBuilder();
    
    result.append("{");
    result.append("creationTime:" + this.creationTime );
    result.append(", mounted:" + this.mounted );
    result.append(", devId:" + this.devId );
    result.append(", freeStart:" + this.freeStart );
    result.append(", freeEnd:" + this.freeEnd );
    result.append(", root:" + this.root );
    result.append(", maxBlocks:" + this.maxBlocks );
    result.append("}");

    return result.toString();
  }
}


