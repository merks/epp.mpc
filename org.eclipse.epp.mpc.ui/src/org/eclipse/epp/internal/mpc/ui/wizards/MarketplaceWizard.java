/*******************************************************************************
 * Copyright (c) 2010 The Eclipse Foundation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * 	The Eclipse Foundation - initial API and implementation
 *******************************************************************************/
package org.eclipse.epp.internal.mpc.ui.wizards;

import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IBundleGroup;
import org.eclipse.core.runtime.IBundleGroupProvider;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.epp.internal.mpc.ui.MarketplaceClientUi;
import org.eclipse.epp.internal.mpc.ui.MarketplaceClientUiPlugin;
import org.eclipse.epp.internal.mpc.ui.catalog.MarketplaceCatalog;
import org.eclipse.epp.internal.mpc.ui.operations.ProvisioningOperation;
import org.eclipse.epp.internal.mpc.ui.operations.ProvisioningOperation.OperationType;
import org.eclipse.epp.mpc.ui.CatalogDescriptor;
import org.eclipse.equinox.internal.p2.discovery.model.CatalogItem;
import org.eclipse.equinox.internal.p2.ui.discovery.wizards.CatalogPage;
import org.eclipse.equinox.internal.p2.ui.discovery.wizards.DiscoveryWizard;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.osgi.util.NLS;
import org.eclipse.ui.statushandlers.StatusManager;

/**
 * @author David Green
 */
public class MarketplaceWizard extends DiscoveryWizard {

	private static final String PREF_DEFAULT_CATALOG = CatalogDescriptor.class.getSimpleName();

	private CatalogSelectionPage catalogSelectionPage;

	private Set<String> installedFeatures;

	private final SelectionModel selectionModel;

	public MarketplaceWizard(MarketplaceCatalog catalog, MarketplaceCatalogConfiguration configuration) {
		super(catalog, configuration);
		setWindowTitle(Messages.MarketplaceWizard_eclipseSolutionCatalogs);
		selectionModel = new SelectionModel(this);
	}

	@Override
	public MarketplaceCatalogConfiguration getConfiguration() {
		return (MarketplaceCatalogConfiguration) super.getConfiguration();
	}

	@Override
	public MarketplaceCatalog getCatalog() {
		return (MarketplaceCatalog) super.getCatalog();
	}

	@Override
	protected CatalogPage doCreateCatalogPage() {
		return new MarketplacePage(getCatalog(), getConfiguration());
	}

	@Override
	public void addPages() {
		doDefaultCatalogSelection();
		if (getConfiguration().getCatalogDescriptors().size() > 1) {
			addPage(getCatalogSelectionPage());
		}
		super.addPages();
		addPage(new FeatureSelectionWizardPage());
	}

	public CatalogSelectionPage getCatalogSelectionPage() {
		if (catalogSelectionPage == null) {
			catalogSelectionPage = new CatalogSelectionPage(getConfiguration());
		}
		return catalogSelectionPage;
	}

	@Override
	public IWizardPage getStartingPage() {
		if (getConfiguration().getCatalogDescriptor() != null) {
			return getCatalogPage();
		}
		return super.getStartingPage();
	}

	private void doDefaultCatalogSelection() {
		if (getConfiguration().getCatalogDescriptor() == null) {
			String defaultCatalogUrl = MarketplaceClientUiPlugin.getInstance().getPreferenceStore().getString(
					PREF_DEFAULT_CATALOG);
			// if a preferences was set, we default to that catalog descriptor
			if (defaultCatalogUrl != null && defaultCatalogUrl.length() > 0) {
				for (CatalogDescriptor descriptor : getConfiguration().getCatalogDescriptors()) {
					URL url = descriptor.getUrl();
					try {
						if (url.toURI().toString().equals(defaultCatalogUrl)) {
							getConfiguration().setCatalogDescriptor(descriptor);
							break;
						}
					} catch (URISyntaxException e) {
						// ignore
					}
				}
			}
			// if no preference was set or we could not find the descriptor then we default to eclipse.org
			if (getConfiguration().getCatalogDescriptor() == null) {
				for (CatalogDescriptor descriptor : getConfiguration().getCatalogDescriptors()) {
					URL url = descriptor.getUrl();

					String host = url.getHost();
					if (host.endsWith(".eclipse.org")) { //$NON-NLS-1$
						getConfiguration().setCatalogDescriptor(descriptor);
						break;
					}
				}
			}
		}
	}

	@Override
	public void dispose() {
		if (getConfiguration().getCatalogDescriptor() != null) {
			// remember the catalog for next time.
			try {
				MarketplaceClientUiPlugin.getInstance().getPreferenceStore().setValue(PREF_DEFAULT_CATALOG,
						getConfiguration().getCatalogDescriptor().getUrl().toURI().toString());
			} catch (URISyntaxException e) {
				// ignore
			}
		}
		if (getCatalog() != null) {
			getCatalog().dispose();
		}
		super.dispose();
	}

	@Override
	public boolean performFinish() {
		try {
			// FIXME: this is a placeholder until bug 305441 is complete
			Map<CatalogItem, Operation> itemToOperation = getSelectionModel().getItemToOperation();
			OperationType operationType = null;
			List<CatalogItem> items = new ArrayList<CatalogItem>();
			for (Map.Entry<CatalogItem, Operation> entry : itemToOperation.entrySet()) {
				OperationType entryOperationType = entry.getValue().getOperationType();
				if (entryOperationType != null) {
					if (operationType == null || operationType == OperationType.UPDATE) {
						operationType = entryOperationType;
					}
					items.add(entry.getKey());
				}
			}
			IRunnableWithProgress runner = new ProvisioningOperation(operationType, itemToOperation.keySet(),
					getSelectionModel().getSelectedFeatureDescriptors());
			getContainer().run(true, true, runner);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			IStatus status;
			if (cause instanceof CoreException) {
				status = ((CoreException) cause).getStatus();
			} else {
				status = new Status(IStatus.ERROR, MarketplaceClientUi.BUNDLE_ID, NLS.bind(
						Messages.MarketplaceWizard_problemsPerformingProvisioningOperation,
						new Object[] { cause.getMessage() }), cause);
			}
			StatusManager.getManager().handle(status, StatusManager.SHOW | StatusManager.BLOCK | StatusManager.LOG);
			return false;
		} catch (InterruptedException e) {
			// canceled
			return false;
		}
		return true;
	}

	@Override
	public MarketplacePage getCatalogPage() {
		return (MarketplacePage) super.getCatalogPage();
	}

	protected synchronized Set<String> getInstalledFeatures() {
		if (installedFeatures == null) {
			Set<String> features = new HashSet<String>();
			IBundleGroupProvider[] bundleGroupProviders = Platform.getBundleGroupProviders();
			for (IBundleGroupProvider provider : bundleGroupProviders) {
				IBundleGroup[] bundleGroups = provider.getBundleGroups();
				for (IBundleGroup group : bundleGroups) {
					features.add(group.getIdentifier());
				}
			}
			installedFeatures = features;
		}
		return installedFeatures;
	}

	public SelectionModel getSelectionModel() {
		return selectionModel;
	}
}
