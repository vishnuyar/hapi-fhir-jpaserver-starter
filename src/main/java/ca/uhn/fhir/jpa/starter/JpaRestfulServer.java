package ca.uhn.fhir.jpa.starter;

import javax.servlet.ServletException;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.ActivityDefinition;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Endpoint;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.Measure;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.ValueSet;
import org.opencds.cqf.common.evaluation.EvaluationProviderFactory;
import org.opencds.cqf.common.retrieve.JpaFhirRetrieveProvider;
import org.opencds.cqf.cql.engine.fhir.searchparam.SearchParameterResolver;
import org.opencds.cqf.library.r4.NarrativeProvider;
import org.opencds.cqf.r4.evaluation.ProviderFactory;
import org.opencds.cqf.r4.providers.ActivityDefinitionApplyProvider;
import org.opencds.cqf.r4.providers.ApplyCqlOperationProvider;
import org.opencds.cqf.r4.providers.CacheValueSetsProvider;
import org.opencds.cqf.r4.providers.ClaimProvider;
import org.opencds.cqf.r4.providers.CodeSystemUpdateProvider;
import org.opencds.cqf.r4.providers.CqlExecutionProvider;
import org.opencds.cqf.r4.providers.HQMFProvider;
import org.opencds.cqf.r4.providers.JpaTerminologyProvider;
import org.opencds.cqf.r4.providers.LibraryOperationsProvider;
import org.opencds.cqf.r4.providers.MeasureOperationsProvider;
import org.opencds.cqf.r4.providers.PatientProvider;
import org.opencds.cqf.r4.providers.PlanDefinitionApplyProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.provider.BaseJpaResourceProvider;
import ca.uhn.fhir.jpa.rp.r4.LibraryResourceProvider;
import ca.uhn.fhir.jpa.rp.r4.MeasureResourceProvider;
import ca.uhn.fhir.jpa.rp.r4.ValueSetResourceProvider;
import ca.uhn.fhir.jpa.term.api.ITermReadSvcR4;

@Import(AppProperties.class)
public class JpaRestfulServer extends BaseJpaRestfulServer {

  @Autowired
  AppProperties appProperties;

  private static final long serialVersionUID = 1L;
  private ApplicationContext appCtx;
  public JpaRestfulServer() {
    super();
  }

  @Override
  protected void initialize() throws ServletException {
    super.initialize();

    // Add your own customization here
    appCtx = (ApplicationContext) getServletContext()
        .getAttribute("org.springframework.web.context.WebApplicationContext.ROOT");
    DaoRegistry daoRegistry = appCtx.getBean(DaoRegistry.class);
    JpaTerminologyProvider localSystemTerminologyProvider = new JpaTerminologyProvider(
        appCtx.getBean("terminologyService", ITermReadSvcR4.class), getFhirContext(),
        (ValueSetResourceProvider) this.getResourceProvider(ValueSet.class));
    EvaluationProviderFactory providerFactory = new ProviderFactory(getFhirContext(), daoRegistry,
        localSystemTerminologyProvider);
    resolveProviders(providerFactory, localSystemTerminologyProvider, daoRegistry);

  }

  private void resolveProviders(EvaluationProviderFactory providerFactory,
      JpaTerminologyProvider localSystemTerminologyProvider, DaoRegistry registry) throws ServletException {
    NarrativeProvider narrativeProvider = new NarrativeProvider();
    HQMFProvider hqmfProvider = new HQMFProvider();

    // Code System Update
    CodeSystemUpdateProvider csUpdate = new CodeSystemUpdateProvider(registry.getResourceDao(ValueSet.class),
        registry.getResourceDao(CodeSystem.class));
    this.registerProvider(csUpdate);

    // Cache Value Sets
    CacheValueSetsProvider cvs = new CacheValueSetsProvider(registry.getSystemDao(),
        registry.getResourceDao(Endpoint.class));
    this.registerProvider(cvs);

    // Library processing
    LibraryOperationsProvider libraryProvider = new LibraryOperationsProvider(
        (LibraryResourceProvider) this.getResourceProvider(Library.class), narrativeProvider);
    this.registerProvider(libraryProvider);

    // CQL Execution
    CqlExecutionProvider cql = new CqlExecutionProvider(libraryProvider, providerFactory);
    this.registerProvider(cql);

    // Bundle processing
    ApplyCqlOperationProvider bundleProvider = new ApplyCqlOperationProvider(providerFactory,
        registry.getResourceDao(Bundle.class));
    this.registerProvider(bundleProvider);

    // Measure processing
    MeasureOperationsProvider measureProvider = new MeasureOperationsProvider(registry, providerFactory,
        narrativeProvider, hqmfProvider, (LibraryResourceProvider) this.getResourceProvider(Library.class),
        (MeasureResourceProvider) this.getResourceProvider(Measure.class));
    this.registerProvider(measureProvider);

    IFhirResourceDao<Patient> patientDao = registry.getResourceDao(Patient.class);
    IFhirResourceDao<Coverage> coverageDao = registry.getResourceDao(Coverage.class);

    PatientProvider patientRp = new PatientProvider(registry.getSystemDao(), coverageDao);
    patientRp.setDao(patientDao);
    registerProvider(patientRp);

    IFhirResourceDao<Claim> claimDao = registry.getResourceDao(Claim.class);
    ;
    ClaimProvider claimRp = new ClaimProvider(appCtx);
    claimRp.setDao(claimDao);
    registerProvider(claimRp);

    // ActivityDefinition processing
    ActivityDefinitionApplyProvider actDefProvider = new ActivityDefinitionApplyProvider(getFhirContext(), cql,
        registry.getResourceDao(ActivityDefinition.class));
    this.registerProvider(actDefProvider);

    JpaFhirRetrieveProvider localSystemRetrieveProvider = new JpaFhirRetrieveProvider(registry,
        new SearchParameterResolver(getFhirContext()));

    // PlanDefinition processing
    PlanDefinitionApplyProvider planDefProvider = new PlanDefinitionApplyProvider(getFhirContext(), actDefProvider,
        registry.getResourceDao(PlanDefinition.class), registry.getResourceDao(ActivityDefinition.class), cql);
    this.registerProvider(planDefProvider);

  }

  protected <T extends IBaseResource> BaseJpaResourceProvider<T> getResourceProvider(Class<T> clazz) {
    return (BaseJpaResourceProvider<T>) this.getResourceProviders().stream()
        .filter(x -> x.getResourceType().getSimpleName().equals(clazz.getSimpleName())).findFirst().get();
  }

}
