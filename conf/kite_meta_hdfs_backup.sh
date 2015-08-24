# Set and uncomment the following environment variables.
# Add the script to the crontab e.g. like this:
# 59 22 * * * $LOCATION_TO_SCRIPT

# export KITE_META=kite_meta
# export TMP_BACKUP_DIR=/tmp/kite_meta_backup
# export KITE_META_PARENT_DIR=$HOME
# export HDFS_BACKUP_DIR=/user/$USER/backup

# Copy the kite meta dir to a temporary location for cleansing.
rm -r $TMP_BACKUP_DIR
mkdir $TMP_BACKUP_DIR
cp -r $KITE_META_PARENT_DIR/$KITE_META $TMP_BACKUP_DIR/

# Remove unnecessary lines from tags.journal to save space.
for FILE in $TMP_BACKUP_DIR/$KITE_META/*/tags.journal
do
  grep -v '/!tmp' $FILE > $TMP_BACKUP_DIR/tags.journal.tmp
  cp $TMP_BACKUP_DIR/tags.journal.tmp $FILE
  rm $TMP_BACKUP_DIR/tags.journal.tmp 
done

# Copy a backup to HDFS.
export CURRENT_DATE=$(date +"%y%m%d")
hadoop fs -mkdir -p $HDFS_BACKUP_DIR/$CURRENT_DATE
hadoop fs -put  $TMP_BACKUP_DIR/$KITE_META $HDFS_BACKUP_DIR/$CURRENT_DATE/

