package org.jetbrains.plugins.ideaConfigurationServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.intellij.ide.ApplicationLoadListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ex.ApplicationEx2;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.StateStorage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.components.impl.stores.FileBasedStorage;
import com.intellij.openapi.components.impl.stores.StateStorageManager;
import com.intellij.openapi.components.impl.stores.StorageUtil;
import com.intellij.openapi.components.impl.stores.StreamProvider;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.SchemesManagerFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectEx;
import com.intellij.openapi.project.impl.ProjectLifecycleListener;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.util.SingleAlarm;

public class IcsManager implements ApplicationLoadListener, Disposable
{
	static final Logger LOG = Logger.getInstance(IcsManager.class);

	public static final String PLUGIN_NAME = "Idea Configuration Server";

	private final IcsSettings settings = new IcsSettings();

	private final RepositoryManager myRepositoryManager = new GitRepositoryManager();

	private IcsStatus myStatus;

	protected final SingleAlarm commitAlarm = new SingleAlarm(new Runnable()
	{
		@Override
		public void run()
		{
			ProgressManager.getInstance().run(new Task.Backgroundable(null, IcsBundle.message("task.push.title"))
			{
				@Override
				public void run(@NotNull ProgressIndicator indicator)
				{
					awaitCallback(indicator, myRepositoryManager.commit(), getTitle());
				}
			});
		}
	}, settings.commitDelay);

	private static void awaitCallback(@NotNull ProgressIndicator indicator, @NotNull ActionCallback callback, @NotNull String title)
	{
		while(!callback.isProcessed())
		{
			try
			{
				//noinspection BusyWait
				Thread.sleep(100);
			}
			catch(InterruptedException e)
			{
				break;
			}
			if(indicator.isCanceled())
			{
				String message = title + " canceled";
				LOG.warn(message);
				callback.reject(message);
				break;
			}
		}
	}

	public static IcsManager getInstance()
	{
		return ApplicationLoadListener.EP_NAME.findExtension(IcsManager.class);
	}

	public static File getPluginSystemDir()
	{
		return new File(PathManager.getSystemPath(), "ideaConfigurationServer");
	}

	public IcsStatus getStatus()
	{
		return myStatus;
	}

	public void setStatus(@NotNull IcsStatus value)
	{
		if(myStatus != value)
		{
			myStatus = value;
			ApplicationManager.getApplication().getMessageBus().syncPublisher(StatusListener.TOPIC).statusChanged(value);
		}
	}

	private void registerApplicationLevelProviders(Application application)
	{
		try
		{
			settings.load();
		}
		catch(Exception e)
		{
			LOG.error(e);
		}

		connectAndUpdateRepository();

		IcsStreamProvider streamProvider = new IcsStreamProvider(null)
		{
			@NotNull
			@Override
			public Collection<String> listSubFiles(@NotNull String fileSpec, @NotNull RoamingType roamingType)
			{
				return myRepositoryManager.listSubFileNames(IcsUrlBuilder.buildPath(fileSpec, roamingType, null));
			}

			@Override
			public void delete(@NotNull String fileSpec, @NotNull RoamingType roamingType)
			{
				myRepositoryManager.deleteAsync(IcsUrlBuilder.buildPath(fileSpec, roamingType, null));
				commitAlarm.cancelAndRequest();
			}
		};
		StateStorageManager storageManager = ((ApplicationEx2) application).getStateStore().getStateStorageManager();
		storageManager.setStreamProvider(streamProvider);

		Collection<String> storageFileNames = storageManager.getStorageFileNames();
		if(!storageFileNames.isEmpty())
		{
			updateStoragesFromStreamProvider(storageManager, storageFileNames);
			SchemesManagerFactory.getInstance().updateConfigFilesFromStreamProviders();
		}
	}

	private void registerProjectLevelProviders(Project project)
	{
		StateStorageManager storageManager = ((ProjectEx) project).getStateStore().getStateStorageManager();

		StateStorage workspaceFileStorage = storageManager.getStateStorage(StoragePathMacros.WORKSPACE_FILE, RoamingType.PER_USER);
		LOG.assertTrue(workspaceFileStorage != null);
		ProjectId projectId = workspaceFileStorage.getState(new ProjectId(), "IcsProjectId", ProjectId.class, null);
		if(projectId == null || projectId.uid == null)
		{
			// not mapped, if user wants, he can map explicitly, we don't suggest
			// we cannot suggest "map to ICS" for any project that user opens,
			// it will be annoying
			return;
		}

		storageManager.setStreamProvider(new IcsStreamProvider(projectId.uid)
		{
			@Override
			protected boolean isAutoCommit(String fileSpec, RoamingType roamingType)
			{
				return !StorageUtil.isProjectOrModuleFile(fileSpec);
			}

			@Override
			public boolean isApplicable(@NotNull String fileSpec, @NotNull RoamingType roamingType)
			{
				if(StorageUtil.isProjectOrModuleFile(fileSpec))
				{
					// applicable only if file was committed to Settings Server explicitly
					return myRepositoryManager.has(IcsUrlBuilder.buildPath(fileSpec, roamingType, projectId));
				}

				return settings.shareProjectWorkspace || !fileSpec.equals(StoragePathMacros.WORKSPACE_FILE);
			}
		});

		updateStoragesFromStreamProvider(storageManager, storageManager.getStorageFileNames());
	}

	private void connectAndUpdateRepository()
	{
		setStatus(myRepositoryManager.isValid() ? IcsStatus.OPENED : IcsStatus.OPEN_FAILED);
		if(myStatus == IcsStatus.OPENED && settings.updateOnStart)
		{
			myRepositoryManager.updateRepository();
		}
	}

	public IcsSettings getSettings()
	{
		return settings;
	}

	public RepositoryManager getRepositoryManager()
	{
		return myRepositoryManager;
	}

	public String getStatusText()
	{
		switch(myStatus)
		{
			case OPEN_FAILED:
				return "Open repository failed";
			case UPDATE_FAILED:
				return "Update repository failed";
			default:
				return "Unknown";
		}
	}

	private static void updateStoragesFromStreamProvider(final StateStorageManager appStorageManager, final Collection<String> storageFileNames)
	{
		for(String storageFileName : storageFileNames)
		{
			StateStorage stateStorage = appStorageManager.getFileStateStorage(storageFileName);
			if(stateStorage instanceof FileBasedStorage)
			{
				try
				{
					FileBasedStorage fileBasedStorage = (FileBasedStorage) stateStorage;
					fileBasedStorage.resetProviderCache();
					fileBasedStorage.updateFileExternallyFromStreamProviders();
				}
				catch(Throwable e)
				{
					LOG.debug(e);
				}
			}
		}
	}

	@Override
	public void dispose()
	{
	}

	public ActionCallback sync()
	{
		commitAlarm.cancel();
		final ActionCallback actionCallback = new ActionCallback(3);
		ProgressManager.getInstance().run(new Task.Modal(null, IcsBundle.message("task.sync.title"), true)
		{
			@Override
			public void run(@NotNull ProgressIndicator indicator)
			{
				indicator.setIndeterminate(true);
				ApplicationManager.getApplication().invokeAndWait(new Runnable()
				{
					@Override
					public void run()
					{
						ApplicationManager.getApplication().saveSettings();
					}
				}, ModalityState.any());
				commitAlarm.cancel();

				myRepositoryManager.commit().notify(actionCallback);
				myRepositoryManager.pull(indicator).notify(actionCallback);
				ActionCallback lastActionCallback = myRepositoryManager.push(indicator).notify(actionCallback);
				awaitCallback(indicator, lastActionCallback, getTitle());
			}
		});
		return actionCallback;
	}

	private class IcsStreamProvider extends StreamProvider
	{
		protected final String projectId;

		public IcsStreamProvider(@Nullable String projectId)
		{
			this.projectId = projectId;
		}

		@Override
		public final void saveContent(@NotNull String fileSpec, @NotNull byte[] content, int size, @NotNull RoamingType roamingType,
				boolean async)
		{
			myRepositoryManager.write(IcsUrlBuilder.buildPath(fileSpec, roamingType, projectId), content, size, async);
			if(isAutoCommit(fileSpec, roamingType))
			{
				commitAlarm.cancelAndRequest();
			}
		}

		protected boolean isAutoCommit(String fileSpec, RoamingType roamingType)
		{
			return true;
		}

		@Override
		@Nullable
		public InputStream loadContent(@NotNull String fileSpec, @NotNull RoamingType roamingType) throws IOException
		{
			return myRepositoryManager.read(IcsUrlBuilder.buildPath(fileSpec, roamingType, projectId));
		}

		@Override
		public void delete(@NotNull String fileSpec, @NotNull RoamingType roamingType)
		{

		}

		@Override
		public boolean isEnabled()
		{
			return myStatus == IcsStatus.OPENED;
		}
	}

	@Override
	public void beforeApplicationLoaded(Application application)
	{
		if(application.isUnitTestMode())
		{
			return;
		}

		registerApplicationLevelProviders(application);

		ApplicationManager.getApplication().getMessageBus().connect().subscribe(ProjectLifecycleListener.TOPIC,
				new ProjectLifecycleListener.Adapter()
		{
			@Override
			public void beforeProjectLoaded(@NotNull Project project)
			{
				if(!project.isDefault())
				{
					getInstance().registerProjectLevelProviders(project);
				}
			}
		});
	}
}