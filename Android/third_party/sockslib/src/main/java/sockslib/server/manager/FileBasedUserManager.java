/*
 * Copyright 2015-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sockslib.server.manager;

import com.google.common.base.Strings;
import sockslib.utils.PathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * The class <code>FileBasedUserManager</code> represents an user manager which can manage users
 * in a file.
 *
 * @author Youchao Feng
 * @version 1.0
 * @date Aug 28, 2015
 */
public class FileBasedUserManager implements UserManager {

  private static final Logger logger = LoggerFactory.getLogger(FileBasedUserManager.class);

  private File storeFile;
  private StoreType storeType = StoreType.PROPERTIES;
  private Map<String, User> managedUsers;
  private boolean autoReload = false;
  private long reloadAfter = 20000;
  private AutoReloadService autoReloadService;

  public FileBasedUserManager(File storeFile, StoreType storeType) throws IOException {
    this.storeFile = checkNotNull(storeFile, "Argument [storeFile] may not be null");
    this.storeType = checkNotNull(storeType, "Argument [storeType] may not be null");
    loadFromFile();
  }

  public FileBasedUserManager(File storeFile) throws IOException {
    this(storeFile, StoreType.PROPERTIES);
  }

  public FileBasedUserManager(String storeFile, boolean autoReload, long reloadAfter) throws
      IOException {
    storeFile = PathUtil.getAbstractPath(storeFile);
    this.storeFile = new File(storeFile);
    this.autoReload = autoReload;
    this.reloadAfter = reloadAfter;
    loadFromFile();
    if (this.autoReload) {
      autoReloadService = new AutoReloadService(this.reloadAfter);
      autoReloadService.start();
    }
  }

  public FileBasedUserManager(String storeFile) throws IOException {
    this(storeFile, false, 0);
  }

  private void synchronizedWithFile() {

  }

  private void loadFromFile() throws IOException {
    if (managedUsers == null) {
      managedUsers = new HashMap<>();
    }
    Properties properties = new Properties();
    properties.load(new FileInputStream(storeFile));
    Enumeration enum1 = properties.propertyNames();
    while (enum1.hasMoreElements()) {
      String username = (String) enum1.nextElement();
      String password = properties.getProperty(username);
      User user = new User();
      user.setUsername(username);
      user.setPassword(password);
      managedUsers.put(username, user);
    }
  }

  @Override
  public void create(User user) {
    checkArgument(!(user == null || user.getUsername() == null), "User or username can't be null");
    managedUsers.put(user.getUsername(), user);
    logger.warn("Create a temporary user[{}]", user.getUsername());
  }

  @Override
  public UserManager addUser(String username, String password) {
    if (username == null) {
      throw new IllegalArgumentException("Username can't be null");
    }
    logger.warn("Create a temporary user[{}]", username);
    managedUsers.put(username, new User(username, password));
    return this;
  }

  @Override
  public User check(String username, String password) {
    User user = find(username);
    if (user != null && user.getPassword() != null && user.getPassword().equals(password)) {
      return user;
    }
    return null;
  }

  @Override
  public void delete(String username) {
    managedUsers.remove(username);
  }

  @Override
  public List<User> findAll() {
    return null;
  }

  @Override
  public void update(User user) {
    if (user == null) {
      throw new IllegalArgumentException("User can't null");
    }
    if (Strings.isNullOrEmpty(user.getUsername())) {
      throw new IllegalArgumentException("Username of the user can't be null or empty");
    }
    managedUsers.put(user.getUsername(), user);
    logger.warn("Update user[{}] temporarily", user.getUsername());
  }

  @Override
  public User find(String username) {
    if (Strings.isNullOrEmpty(username)) {
      throw new IllegalArgumentException("Username can't be null or empty");
    }
    return managedUsers.get(username);
  }

  public File getStoreFile() {
    return storeFile;
  }

  public void setStoreFile(File storeFile) {
    this.storeFile = storeFile;
  }

  public StoreType getStoreType() {
    return storeType;
  }

  public void setStoreType(StoreType storeType) {
    this.storeType = storeType;
  }

  public Map<String, User> getManagedUsers() {
    return managedUsers;
  }

  public void setManagedUsers(Map<String, User> managedUsers) {
    this.managedUsers = managedUsers;
  }

  public boolean isAutoReload() {
    return autoReload;
  }

  public void setAutoReload(boolean autoReload) {
    this.autoReload = autoReload;
  }

  public long getReloadAfter() {
    return reloadAfter;
  }

  public void setReloadAfter(long reloadAfter) {
    this.reloadAfter = reloadAfter;
  }

  public AutoReloadService getAutoReloadService() {
    return autoReloadService;
  }

  public void setAutoReloadService(AutoReloadService autoReloadService) {
    this.autoReloadService = autoReloadService;
  }

  public enum StoreType {
    PROPERTIES
  }


  private class AutoReloadService implements Runnable {

    private Thread thread;
    private long reloadAfter;
    private boolean stop;

    public AutoReloadService(long reloadAfter) {
      this.reloadAfter = reloadAfter;
    }

    public void start() {
      stop = false;
      thread = new Thread(this, "AutoReloadService");
      thread.setDaemon(true);
      thread.start();
      ;
    }

    public void stop() {
      stop = true;
      if (thread != null) {
        thread.interrupt();
      }
    }

    @Override
    public void run() {
      while (!stop) {
        try {
          Thread.sleep(reloadAfter);
        } catch (InterruptedException e) {
          logger.error(e.getMessage(), e);
        }
        try {
          loadFromFile();
        } catch (IOException e) {
          logger.error(e.getMessage(), e);
        }
      }
    }
  }
}
