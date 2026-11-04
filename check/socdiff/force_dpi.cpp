// Pull Verilator's DPI export object out of the model archive. The weak
// ideal_backend_resp would otherwise satisfy the link before that object
// is opened.
extern "C" void ideal_backend_anchor();

extern "C" void ideal_backend_force_link() { ideal_backend_anchor(); }
